-- =====================================================================
--  12) TV KOUAMÉ : programmation variée (remplace la rotation fixe « film, 5 chansons, série, 5 chansons »)
--     - après un film ou une série : TOUJOURS de la musique (1 à 5 chansons, tirées au hasard : rien d'obligatoire)
--     - ensuite on varie : film ou série (on alterne quand les deux existent), jamais deux films/séries à la suite
--     - aucun programme n'est répété avant la fin de son cycle : chaque catégorie avance dans l'ordre et ne
--       reprend du début qu'après avoir passé toutes ses vidéos (la position est gardée d'un jour à l'autre)
--     - un film/une série n'est jamais passé deux fois dans la même journée ; s'il n'en reste plus d'inédit
--       aujourd'hui, la musique continue (dans son propre cycle)
--     - les vidéos retirées de la TV (tv_exclu) et sans titre/durée restent ignorées, comme avant
--  À exécuter une fois dans Supabase › SQL Editor. Prend effet à la prochaine fabrication (00h00 ou
--  « Refaire le programme d'aujourd'hui »). Ne touche ni aux tables ni au programme en cours.
-- =====================================================================
create or replace function public._tv_fabriquer(p_jour date, p_canal text) returns integer
language plpgsql security definer set search_path = public as $$
declare
  e record; r record; rc record; rf record; rs record;
  cumul bigint := 0; n int := 0; garde int := 0;
  v_film uuid := null; v_reste bigint := 0; v_duree bigint;
  s_film uuid := null; s_reste bigint := 0;
  etape int := 0; pf int := 0; ps int := 0; pc int := 0;
  d_etape int; d_film int; d_serie int; d_chanson int;
  cat text; pos int;
  deja uuid[] := '{}';
  derniere text := null;     -- catégorie du dernier programme passé
  lourde text := null;       -- dernière catégorie « film ou série » passée
  quota int := 0;            -- chansons à passer avant le prochain film/série
  vues int := 0;             -- chansons passées depuis le dernier film/série
  doit_musique boolean;
begin
  select * into e from public.tv_etat where id = 1;

  -- film qui déborde sur minuit : reçu de la veille (ou, si on refait la journée, celui déjà reçu ce matin)
  if e.jour = p_jour - 1 then
    v_film := e.sortant_film;  v_reste := coalesce(e.sortant_ms, 0);
  elsif e.jour = p_jour then
    v_film := e.entrant_film;  v_reste := coalesce(e.entrant_ms, 0);
  end if;

  -- positions des cycles (une par catégorie) : reprises là où la veille s'est arrêtée
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
      deja := array_append(deja, v_film);
      -- ce film de la veille est suivi de musique, comme tout film
      select f.categorie into derniere from public.films_facebook f where f.id = v_film;
      if derniere in ('film', 'serie') then
        lourde := derniere;
        quota := 1 + floor(random() * 5)::int;
      end if;
    else
      v_film := null; v_reste := 0;
    end if;
  else
    v_film := null; v_reste := 0;
  end if;
  if derniere is null or derniere not in ('film', 'serie') then
    derniere := 'chanson';
    quota := floor(random() * 3)::int;        -- début de journée : 0 à 2 chansons avant le premier film/série
  end if;

  while cumul < 86400000 and garde < 3000 loop
    garde := garde + 1;

    -- prochaine vidéo de chaque catégorie dans son cycle (films/séries : pas deux fois le même jour)
    select * into rc from public._tv_prochain('chanson', pc, deja);
    select * into rf from public._tv_prochain('film',    pf, deja);
    select * into rs from public._tv_prochain('serie',   ps, deja);

    doit_musique := derniere in ('film', 'serie') or (derniere = 'chanson' and vues < quota);
    cat := null;
    if doit_musique and rc.film_id is not null then
      cat := 'chanson';
    elsif rf.film_id is not null and rs.film_id is not null then
      cat := case when lourde = 'film' then 'serie'
                  when lourde = 'serie' then 'film'
                  when random() < 0.5 then 'film' else 'serie' end;
    elsif rf.film_id is not null then
      cat := 'film';
    elsif rs.film_id is not null then
      cat := 'serie';
    elsif rc.film_id is not null then
      cat := 'chanson';
    end if;
    exit when cat is null;                    -- plus aucune vidéo utilisable

    if cat = 'chanson' then r := rc; pos := pc;
    elsif cat = 'film' then r := rf; pos := pf;
    else r := rs; pos := ps;
    end if;

    n := n + 1;
    insert into public.tv_programme (ordre, film_id, canal, debut_ms) values (n, r.film_id, p_canal, cumul);
    cumul := cumul + r.duree;

    if cat = 'film' then pf := r.nouvelle_pos;
    elsif cat = 'serie' then ps := r.nouvelle_pos;
    else pc := r.nouvelle_pos;
    end if;

    if cat = 'chanson' then
      vues := vues + 1;
    else
      deja := array_append(deja, r.film_id);
      lourde := cat;
      quota := 1 + floor(random() * 5)::int;  -- 1 à 5 chansons après ce film/cette série
      vues := 0;
    end if;
    derniere := cat;
  end loop;

  -- ce qui déborde sur minuit sera repris demain à 00h00
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
end $$;

revoke all on function public._tv_fabriquer(date, text) from public, anon, authenticated;
