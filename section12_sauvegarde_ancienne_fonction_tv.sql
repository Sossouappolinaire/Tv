-- SAUVEGARDE de l'ancienne fonction (rotation fixe : film, 5 chansons, série, 5 chansons).
-- Pour revenir en arrière : exécuter ce fichier dans Supabase › SQL Editor.
create or replace function public._tv_fabriquer(p_jour date, p_canal text) returns integer
language plpgsql security definer set search_path = public as $function$
declare
  e record; r record;
  cumul bigint := 0; n int := 0; garde int := 0; vides int := 0;
  v_film uuid := null; v_reste bigint := 0; v_duree bigint;
  s_film uuid := null; s_reste bigint := 0;
  etape int := 0; pf int := 0; ps int := 0; pc int := 0;
  d_etape int; d_film int; d_serie int; d_chanson int;
  cat text; cat_jouee text; pos int;
  deja uuid[] := '{}';
  rotation text[] := array['film','chanson','chanson','chanson','chanson','chanson',
                           'serie','chanson','chanson','chanson','chanson','chanson'];
begin
  select * into e from public.tv_etat where id = 1;

  if e.jour = p_jour - 1 then
    v_film := e.sortant_film;  v_reste := coalesce(e.sortant_ms, 0);
  elsif e.jour = p_jour then
    v_film := e.entrant_film;  v_reste := coalesce(e.entrant_ms, 0);
  end if;

  if e.jour = p_jour then
    etape := e.deb_etape; pf := e.deb_film; ps := e.deb_serie; pc := e.deb_chanson;
  elsif e.jour is not null and e.jour < p_jour then
    etape := e.fin_etape; pf := e.fin_film; ps := e.fin_serie; pc := e.fin_chanson;
  end if;
  d_etape := etape; d_film := pf; d_serie := ps; d_chanson := pc;

  delete from public.tv_programme where true;

  if v_film is not null and v_reste > 0 then
    select duree_ms into v_duree from public.films_facebook where id = v_film and duree_ms > 0;
    if found and v_reste <= v_duree then
      n := 1;
      insert into public.tv_programme (ordre, film_id, canal, debut_ms) values (1, v_film, p_canal, v_reste - v_duree);
      cumul := v_reste;
      deja := array_append(deja, v_film);   -- déjà diffusé aujourd'hui (fin du programme d'hier)
    else
      v_film := null; v_reste := 0;
    end if;
  else
    v_film := null; v_reste := 0;
  end if;

  while cumul < 86400000 and garde < 3000 and vides < 12 loop
    garde := garde + 1;
    cat := rotation[(etape % 12) + 1];
    cat_jouee := cat;
    pos := case cat when 'film' then pf when 'serie' then ps else pc end;
    select * into r from public._tv_prochain(cat, pos, deja);
    if r.film_id is null and cat <> 'chanson' then
      -- plus de film (ou d'épisode) jamais diffusé aujourd'hui : on rejoue les musiques à la place
      cat_jouee := 'chanson';
      select * into r from public._tv_prochain('chanson', pc, deja);
    end if;
    etape := (etape + 1) % 12;
    if r.film_id is null then
      vides := vides + 1;
      continue;
    end if;
    vides := 0;
    n := n + 1;
    insert into public.tv_programme (ordre, film_id, canal, debut_ms) values (n, r.film_id, p_canal, cumul);
    cumul := cumul + r.duree;
    if cat_jouee = 'film' then pf := r.nouvelle_pos;
    elsif cat_jouee = 'serie' then ps := r.nouvelle_pos;
    else pc := r.nouvelle_pos;
    end if;
    if cat_jouee <> 'chanson' then deja := array_append(deja, r.film_id); end if;
  end loop;

  if cumul > 86400000 then
    select t.film_id into s_film from public.tv_programme t order by t.ordre desc limit 1;
    s_reste := cumul - 86400000;
  end if;

  update public.tv_etat
     set jour = p_jour, canal = p_canal,
         entrant_film = v_film, entrant_ms = v_reste,
         sortant_film = s_film, sortant_ms = s_reste,
         deb_etape = d_etape, deb_film = d_film, deb_serie = d_serie, deb_chanson = d_chanson,
         fin_etape = etape, fin_film = pf, fin_serie = ps, fin_chanson = pc
   where id = 1;
  return n;
end $function$;
revoke all on function public._tv_fabriquer(date, text) from public, anon, authenticated;
