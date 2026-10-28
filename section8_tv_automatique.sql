-- =====================================================================
--  8) TV KOUAMÉ AUTOMATIQUE : le site fabrique lui-même le programme, en continu, 24 h sur 24 (relançable)
--     - chaque jour à 00h00 (heure du Bénin) le programme du nouveau jour est fabriqué par le serveur
--     - la journée est TOUJOURS entièrement couverte : si un film finit à 00h34, le film suivant commence à 00h34
--       (le film qui déborde sur minuit continue le lendemain, puis le nouveau programme enchaîne)
--     - aucun film répété tant qu'il en reste d'inédits ; quand ils sont tous passés, un nouveau cycle recommence
--     - séries rangées par épisode, puis les autres films par date d'ajout
--     - le 1er utilisateur qui ouvre TV Kouamé après 00h00 déclenche la fabrication (aucun administrateur requis)
-- =====================================================================
create table if not exists public.tv_programme (
  ordre   int primary key,
  film_id uuid not null references public.films_facebook(id) on delete cascade,
  canal   text not null default 'Kouamé TV'
);
-- début du programme en ms depuis 00h00 du jour (NÉGATIF pour le film commencé la veille et qui continue après minuit)
alter table public.tv_programme add column if not exists debut_ms bigint not null default 0;
alter table public.tv_programme enable row level security;
revoke all on public.tv_programme from anon, authenticated;

create table if not exists public.tv_diffuses (
  film_id uuid primary key references public.films_facebook(id) on delete cascade,
  jour    date not null
);
alter table public.tv_diffuses enable row level security;
revoke all on public.tv_diffuses from anon, authenticated;

create table if not exists public.tv_etat (
  id    int primary key default 1 check (id = 1),
  jour  date,
  canal text not null default 'Kouamé TV'
);
-- film qui continue après minuit : « entrant » = reçu de la veille, « sortant » = laissé pour le lendemain (temps restant en ms)
alter table public.tv_etat add column if not exists entrant_film uuid;
alter table public.tv_etat add column if not exists entrant_ms   bigint not null default 0;
alter table public.tv_etat add column if not exists sortant_film uuid;
alter table public.tv_etat add column if not exists sortant_ms   bigint not null default 0;
alter table public.tv_etat enable row level security;
revoke all on public.tv_etat from anon, authenticated;
insert into public.tv_etat (id) values (1) on conflict (id) do nothing;

-- Fabrique le programme du jour donné (interne).
create or replace function public._tv_fabriquer(p_jour date, p_canal text) returns int
language plpgsql security definer set search_path = public as $$
declare
  r record; e record;
  cumul bigint := 0; n int := 0; passe int;
  v_film uuid := null; v_reste bigint := 0; v_duree bigint;
  s_film uuid := null; s_reste bigint := 0;
begin
  select * into e from public.tv_etat where id = 1;

  -- le film qui déborde sur minuit : reçu de la veille (ou, si on refait la journée, celui déjà reçu ce matin)
  if e.jour = p_jour - 1 then
    v_film := e.sortant_film;  v_reste := coalesce(e.sortant_ms, 0);
  elsif e.jour = p_jour then
    v_film := e.entrant_film;  v_reste := coalesce(e.entrant_ms, 0);
  end if;

  delete from public.tv_programme where true;
  delete from public.tv_diffuses where jour = p_jour;

  if v_film is not null and v_reste > 0 then
    select duree_ms into v_duree from public.films_facebook where id = v_film and duree_ms > 0;
    if found and v_reste <= v_duree then
      n := 1;
      insert into public.tv_programme (ordre, film_id, canal, debut_ms) values (1, v_film, p_canal, v_reste - v_duree);
      cumul := v_reste;
    else
      v_film := null; v_reste := 0;
    end if;
  else
    v_film := null; v_reste := 0;
  end if;

  if exists (select 1 from public.films_facebook where duree_ms > 0) then
    -- passe 1 : films jamais diffusés dans le cycle ; passe 2 : nouveau cycle (films pas encore dans la journée) ;
    -- passes suivantes : tout le catalogue (seulement si le catalogue entier dure moins de 24 h)
    for passe in 1..60 loop
      exit when cumul >= 86400000;
      if passe = 2 then delete from public.tv_diffuses where true; end if;
      for r in
        select f.id, f.duree_ms
          from public.films_facebook f
         where f.duree_ms > 0
           and ( (passe = 1 and not exists (select 1 from public.tv_diffuses d where d.film_id = f.id))
              or (passe = 2 and not exists (select 1 from public.tv_programme t where t.film_id = f.id))
              or  passe >= 3 )
         order by coalesce(f.serie, '~'), coalesce(f.episode, 0), f.cree_le
      loop
        exit when cumul >= 86400000;                 -- le dernier film peut déborder sur minuit : il continuera demain
        n := n + 1;
        insert into public.tv_programme (ordre, film_id, canal, debut_ms) values (n, r.id, p_canal, cumul);
        insert into public.tv_diffuses (film_id, jour) values (r.id, p_jour)
          on conflict (film_id) do update set jour = excluded.jour;
        cumul := cumul + r.duree_ms;
      end loop;
    end loop;
  end if;

  -- ce qui déborde sur minuit sera repris demain à 00h00
  if cumul > 86400000 then
    select t.film_id into s_film from public.tv_programme t order by t.ordre desc limit 1;
    s_reste := cumul - 86400000;
  end if;

  update public.tv_etat
     set jour = p_jour, canal = p_canal,
         entrant_film = v_film, entrant_ms = v_reste,
         sortant_film = s_film, sortant_ms = s_reste
   where id = 1;
  return n;
end $$;

-- Fabrique le programme du jour s'il n'existe pas encore (interne).
create or replace function public._tv_si_besoin() returns void
language plpgsql security definer set search_path = public as $$
declare v_jour date := (now() at time zone 'Africa/Porto-Novo')::date; v_canal text;
begin
  perform pg_advisory_xact_lock(8675309);            -- un seul fabricant à la fois
  if (select jour from public.tv_etat where id = 1) is distinct from v_jour then
    select canal into v_canal from public.tv_etat where id = 1;
    perform public._tv_fabriquer(v_jour, coalesce(v_canal, 'Kouamé TV'));
  end if;
end $$;

-- Lu par tous les utilisateurs connectés : heure du serveur + programme en continu (debut_ms < 0 = film commencé la veille).
create or replace function public.tv_planning() returns json
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'connexion requise'; end if;
  perform public._tv_si_besoin();
  return json_build_object(
    'jour', (now() at time zone 'Africa/Porto-Novo')::date,
    'maintenant_ms', (extract(epoch from (now() at time zone 'Africa/Porto-Novo')::time) * 1000)::bigint,
    'programmes', coalesce((
      select json_agg(json_build_object('ordre', t.ordre, 'canal', t.canal, 'titre', f.titre, 'duree_ms', f.duree_ms,
                                        'debut_ms', t.debut_ms, 'lien', f.lien, 'serie', f.serie, 'episode', f.episode)
                      order by t.ordre)
        from public.tv_programme t join public.films_facebook f on f.id = t.film_id
       where f.duree_ms > 0), '[]'::json));
end $$;

-- Administrateur : refabrique le programme d'AUJOURD'HUI (nom du canal modifiable).
create or replace function public.admin_tv_generer(p_canal text default 'Kouamé TV') returns json
language plpgsql security definer set search_path = public as $$
declare n int; total bigint; v_canal text := left(coalesce(nullif(trim(p_canal), ''), 'Kouamé TV'), 60);
begin
  if not public.est_admin() then raise exception 'reserve a l administrateur'; end if;
  perform pg_advisory_xact_lock(8675309);
  n := public._tv_fabriquer((now() at time zone 'Africa/Porto-Novo')::date, v_canal);
  select coalesce(sum(f.duree_ms), 0) into total
    from public.tv_programme t join public.films_facebook f on f.id = t.film_id;
  return json_build_object('ok', true, 'nombre', n, 'duree_ms', total);
end $$;

-- Administrateur : remet TV Kouamé à zéro (programme, historique et film en cours de minuit).
create or replace function public.admin_tv_vider() returns json
language plpgsql security definer set search_path = public as $$
begin
  if not public.est_admin() then raise exception 'reserve a l administrateur'; end if;
  delete from public.tv_programme where true;
  delete from public.tv_diffuses where true;
  update public.tv_etat set jour = null, entrant_film = null, entrant_ms = 0, sortant_film = null, sortant_ms = 0 where id = 1;
  return json_build_object('ok', true);
end $$;

revoke all on function public._tv_fabriquer(date, text), public._tv_si_besoin() from public, anon, authenticated;
revoke all on function public.tv_planning(), public.admin_tv_generer(text), public.admin_tv_vider() from public, anon, authenticated;
grant execute on function public.tv_planning(), public.admin_tv_generer(text), public.admin_tv_vider() to authenticated;

-- FACULTATIF (si l'extension pg_cron est activée dans Supabase : Database > Extensions) :
-- fabrique le programme pile à 00h00 du Bénin (= 23h00 UTC) sans attendre le premier utilisateur.
do $$
begin
  create extension if not exists pg_cron;
  perform cron.unschedule('tv-kouame-minuit') where exists (select 1 from cron.job where jobname = 'tv-kouame-minuit');
  perform cron.schedule('tv-kouame-minuit', '0 23 * * *', 'select public._tv_si_besoin()');
exception when others then
  raise notice 'pg_cron indisponible : le programme sera fabriqué par le premier utilisateur après 00h00 (aucun souci).';
end $$;
