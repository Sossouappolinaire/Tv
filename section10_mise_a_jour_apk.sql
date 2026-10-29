-- =====================================================================
--  10) MISE À JOUR DE L'APPLICATION (APK stocké dans la base) — relançable
--      À exécuter UNE FOIS : Supabase > SQL Editor > New query > coller > Run
--      (après supabase_setup.sql, qui crée est_admin())
--
--      - L'administrateur envoie le nouvel APK depuis l'appli (Administration > Mise à jour > « Nouvelle version »).
--      - Tous les comptes connectés voient « Nouvelle mise à jour disponible » avec la date de sortie,
--        touchent « Mettre à jour », et la nouvelle version REMPLACE l'ancienne (sans désinstaller).
-- =====================================================================

-- Dossier privé de stockage des APK (50 Mo maximum par fichier : limite du plan gratuit Supabase).
insert into storage.buckets (id, name, public, file_size_limit)
values ('apk-app', 'apk-app', false, 52428800)
on conflict (id) do update set public = false, file_size_limit = 52428800;

-- Lecture : tout compte connecté. Envoi / remplacement / suppression : administrateur seulement.
drop policy if exists "apk lecture connectes" on storage.objects;
create policy "apk lecture connectes" on storage.objects
    for select to authenticated using (bucket_id = 'apk-app');

drop policy if exists "apk ajout admin" on storage.objects;
create policy "apk ajout admin" on storage.objects
    for insert to authenticated with check (bucket_id = 'apk-app' and public.est_admin());

drop policy if exists "apk maj admin" on storage.objects;
create policy "apk maj admin" on storage.objects
    for update to authenticated using (bucket_id = 'apk-app' and public.est_admin())
    with check (bucket_id = 'apk-app' and public.est_admin());

drop policy if exists "apk suppr admin" on storage.objects;
create policy "apk suppr admin" on storage.objects
    for delete to authenticated using (bucket_id = 'apk-app' and public.est_admin());

-- Une ligne par version publiée.
create table if not exists public.maj_apk (
    id           bigint generated always as identity primary key,
    version_code integer not null unique,          -- numéro interne (doit augmenter à chaque version)
    version_name text    not null,                 -- ex. « 1.17 »
    note         text,                             -- « Quoi de neuf »
    apk          text    not null,                 -- nom du fichier dans le dossier « apk-app »
    taille       bigint,                           -- octets
    obligatoire  boolean not null default false,   -- vrai : impossible de repousser la mise à jour
    cree_le      timestamptz not null default now(),

    constraint maj_version_nom_court check (char_length(version_name) <= 40),
    constraint maj_note_courte       check (note is null or char_length(note) <= 1000),
    constraint maj_apk_court         check (char_length(apk) <= 200)
);
alter table public.maj_apk enable row level security;
revoke all on public.maj_apk from anon, authenticated;

-- Tout compte connecté : dernière version publiée.
create or replace function public.maj_derniere()
returns json language plpgsql stable security definer set search_path = public as $$
declare r public.maj_apk;
begin
    if auth.uid() is null then raise exception 'connexion requise'; end if;
    select * into r from public.maj_apk order by version_code desc limit 1;
    if not found then return json_build_object('ok', true, 'aucune', true); end if;
    return json_build_object('ok', true, 'aucune', false, 'id', r.id,
        'version_code', r.version_code, 'version_name', r.version_name, 'note', coalesce(r.note, ''),
        'apk', r.apk, 'taille', coalesce(r.taille, 0), 'obligatoire', r.obligatoire,
        'cree_ms', (extract(epoch from r.cree_le) * 1000)::bigint);
end $$;

-- Administrateur : publie une version (l'APK doit déjà être envoyé dans le dossier « apk-app »).
create or replace function public.admin_maj_publier(
    p_version_code integer, p_version_name text, p_note text, p_apk text, p_taille bigint, p_obligatoire boolean default false)
returns json language plpgsql security definer set search_path = public, storage as $$
declare v_max integer;
begin
    if not public.est_admin() then raise exception 'reserve a l administrateur'; end if;
    if p_version_code is null or p_version_code < 1 or coalesce(trim(p_version_name), '') = ''
       or coalesce(trim(p_apk), '') = '' then
        return json_build_object('ok', false, 'erreur', 'donnees_invalides');
    end if;
    if not exists (select 1 from storage.objects where bucket_id = 'apk-app' and name = p_apk) then
        return json_build_object('ok', false, 'erreur', 'apk_introuvable');
    end if;
    select coalesce(max(version_code), 0) into v_max from public.maj_apk;
    if p_version_code <= v_max then
        return json_build_object('ok', false, 'erreur', 'version_trop_ancienne', 'derniere', v_max);
    end if;
    insert into public.maj_apk (version_code, version_name, note, apk, taille, obligatoire)
    values (p_version_code, left(trim(p_version_name), 40), left(nullif(trim(coalesce(p_note, '')), ''), 1000),
            p_apk, p_taille, coalesce(p_obligatoire, false));
    return json_build_object('ok', true);
end $$;

-- Administrateur : retire la dernière version publiée (en cas d'erreur d'APK).
create or replace function public.admin_maj_retirer_derniere()
returns json language plpgsql security definer set search_path = public as $$
begin
    if not public.est_admin() then raise exception 'reserve a l administrateur'; end if;
    delete from public.maj_apk where id = (select id from public.maj_apk order by version_code desc limit 1);
    return json_build_object('ok', true);
end $$;

revoke all on function public.maj_derniere(), public.admin_maj_publier(integer, text, text, text, bigint, boolean),
    public.admin_maj_retirer_derniere() from public, anon, authenticated;
grant execute on function public.maj_derniere(), public.admin_maj_publier(integer, text, text, text, bigint, boolean),
    public.admin_maj_retirer_derniere() to authenticated;
