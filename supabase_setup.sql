-- =====================================================================
--  Sossokouamé appel : base de données (comptes, profils, films Facebook)
--  À exécuter UNE FOIS : Supabase > SQL Editor > New query > coller > Run
--  (peut être relancé sans danger)
-- =====================================================================

-- ---------------------------------------------------------------------
--  1) PROFILS : une ligne par compte (nom, email, inscription, dernière connexion)
--     Les mots de passe sont gérés par Supabase Auth (hachés) : jamais stockés ici.
-- ---------------------------------------------------------------------
create table if not exists public.profils (
    id                 uuid primary key references auth.users (id) on delete cascade,
    nom                text,
    email              text,
    cree_le            timestamptz not null default now(),
    derniere_connexion timestamptz,

    constraint nom_court   check (nom   is null or char_length(nom)   <= 100),
    constraint email_court check (email is null or char_length(email) <= 320)
);

alter table public.profils enable row level security;

-- Chacun ne voit et ne modifie QUE sa propre ligne.
drop policy if exists "profil lecture"  on public.profils;
create policy "profil lecture"  on public.profils
    for select to authenticated using (auth.uid() = id);

drop policy if exists "profil ajout"    on public.profils;
create policy "profil ajout"    on public.profils
    for insert to authenticated with check (auth.uid() = id);

drop policy if exists "profil maj"      on public.profils;
create policy "profil maj"      on public.profils
    for update to authenticated using (auth.uid() = id) with check (auth.uid() = id);

grant select, insert, update on public.profils to authenticated;

-- Crée automatiquement le profil dès qu'un compte est créé (même si l'email n'est pas encore confirmé).
create or replace function public.creer_profil()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profils (id, nom, email)
    values (new.id, left(new.raw_user_meta_data ->> 'nom', 100), new.email)
    on conflict (id) do nothing;
    return new;
end;
$$;

drop trigger if exists creer_profil_a_l_inscription on auth.users;
create trigger creer_profil_a_l_inscription
    after insert on auth.users
    for each row execute function public.creer_profil();

-- Comptes déjà existants : on leur crée leur profil.
insert into public.profils (id, nom, email)
select u.id, left(u.raw_user_meta_data ->> 'nom', 100), u.email
from auth.users u
on conflict (id) do nothing;

-- ---------------------------------------------------------------------
--  2) FILMS FACEBOOK : base partagée entre les utilisateurs CONNECTÉS
-- ---------------------------------------------------------------------
create table if not exists public.films_facebook (
    id          uuid primary key default gen_random_uuid(),
    lien        text not null unique,          -- lien de la page Facebook (sans les paramètres de suivi)
    titre       text,
    miniature   text,                          -- petite image JPEG en base64 (les liens d'image Facebook expirent)
    duree_ms    bigint,
    taille_sd   bigint,                        -- octets
    taille_hd   bigint,                        -- octets
    cree_le     timestamptz not null default now(),

    -- garde-fous : seuls de vrais liens Facebook, et des tailles raisonnables
    constraint lien_facebook check (lien ~* '^https?://([a-z0-9-]+\.)*(facebook\.com|fb\.watch|fb\.com)/'),
    constraint lien_court    check (char_length(lien) <= 600),
    constraint titre_court   check (titre is null or char_length(titre) <= 300),
    constraint miniature_max check (miniature is null or char_length(miniature) <= 80000)
);

-- Qui a partagé la vidéo (rempli automatiquement avec le compte connecté).
alter table public.films_facebook
    add column if not exists utilisateur uuid default auth.uid() references auth.users (id) on delete set null;

create index if not exists films_facebook_cree_le_idx on public.films_facebook (cree_le desc);

alter table public.films_facebook enable row level security;

-- Il faut être connecté : on peut LIRE et AJOUTER ; personne ne modifie ni n'efface depuis l'application.
drop policy if exists "lecture publique" on public.films_facebook;
drop policy if exists "ajout public"     on public.films_facebook;
drop policy if exists "lecture connectes" on public.films_facebook;
create policy "lecture connectes" on public.films_facebook
    for select to authenticated using (true);

drop policy if exists "ajout connectes" on public.films_facebook;
create policy "ajout connectes" on public.films_facebook
    for insert to authenticated with check (true);

revoke all on public.films_facebook from anon;
grant select, insert on public.films_facebook to authenticated;

-- =====================================================================
--  RÉGLAGES À FAIRE DANS LE TABLEAU DE BORD SUPABASE (pas en SQL)
--
--  a) Authentication > Sign In / Providers > Email :
--       - « Confirm email » DÉSACTIVÉ  → l'utilisateur crée son compte et entre aussitôt.
--         (Activé, il doit d'abord cliquer le lien reçu par email.)
--       - Longueur minimale du mot de passe : 6
--
--  b) « Mot de passe oublié » : l'application demande un CODE reçu par email.
--     Authentication > Email Templates > « Reset Password » : remplacez le contenu par exemple :
--
--         <h2>Sossokouamé appel</h2>
--         <p>Votre code pour changer le mot de passe :</p>
--         <h1>{{ .Token }}</h1>
--
--  c) Project Settings > API : copiez la clé « anon public » (ou « publishable »)
--     dans Supabase.java (constante CLE). JAMAIS la clé « service_role ».
-- =====================================================================

-- =====================================================================
--  3) ADMINISTRATION (à exécuter après les sections 1 et 2 ; relançable)
--     Le compte administrateur n'est PAS codé dans l'application :
--     1. créez d'abord le compte dans l'application (inscription) avec l'e-mail ci-dessous ;
--     2. cet e-mail est déclaré administrateur ici, côté base : seul lui voit le panneau.
-- =====================================================================
create table if not exists public.admins (email text primary key);
alter table public.admins enable row level security;
revoke all on public.admins from anon, authenticated;
insert into public.admins (email) values ('sossoukouam@gmail.com') on conflict do nothing;

create or replace function public.est_admin()
returns boolean language sql stable security definer set search_path = public as $$
    select exists (select 1 from public.admins where lower(email) = lower(coalesce(auth.jwt() ->> 'email', '')));
$$;
grant execute on function public.est_admin() to authenticated;

-- Présence : dernière activité de chaque compte (« en ligne » = actif depuis moins de 3 minutes).
alter table public.profils add column if not exists derniere_activite timestamptz;

drop policy if exists "profil lecture admin" on public.profils;
create policy "profil lecture admin" on public.profils
    for select to authenticated using (public.est_admin());

-- Films : série + numéro d'épisode, et droits complets pour l'administrateur.
alter table public.films_facebook add column if not exists serie   text;
alter table public.films_facebook add column if not exists episode integer;
alter table public.films_facebook drop constraint if exists serie_courte;
alter table public.films_facebook add constraint serie_courte check (serie is null or char_length(serie) <= 200);

drop policy if exists "films modif admin" on public.films_facebook;
create policy "films modif admin" on public.films_facebook
    for update to authenticated using (public.est_admin()) with check (public.est_admin());
drop policy if exists "films suppr admin" on public.films_facebook;
create policy "films suppr admin" on public.films_facebook
    for delete to authenticated using (public.est_admin());
grant update, delete on public.films_facebook to authenticated;

-- Téléchargements : une ligne à chaque vidéo téléchargée avec succès.
create table if not exists public.telechargements (
    id          uuid primary key default gen_random_uuid(),
    utilisateur uuid default auth.uid() references auth.users (id) on delete set null,
    lien        text,
    cree_le     timestamptz not null default now(),
    constraint lien_court_dl check (lien is null or char_length(lien) <= 600)
);
alter table public.telechargements enable row level security;
drop policy if exists "dl ajout" on public.telechargements;
create policy "dl ajout" on public.telechargements for insert to authenticated with check (true);
drop policy if exists "dl lecture admin" on public.telechargements;
create policy "dl lecture admin" on public.telechargements for select to authenticated using (public.est_admin());
revoke all on public.telechargements from anon;
grant select, insert on public.telechargements to authenticated;

-- Chiffres du tableau de bord (réservé à l'administrateur).
create or replace function public.stats_admin()
returns json language plpgsql stable security definer set search_path = public as $$
begin
    if not public.est_admin() then raise exception 'reserve a l administrateur'; end if;
    return json_build_object(
        'comptes',        (select count(*) from public.profils),
        'en_ligne',       (select count(*) from public.profils where derniere_activite > now() - interval '3 minutes'),
        'telechargements',(select count(*) from public.telechargements),
        'films',          (select count(*) from public.films_facebook));
end;
$$;
grant execute on function public.stats_admin() to authenticated;

-- =====================================================================
--  4) DROITS RESSERRÉS (relançable) : aucun accès pour « anon », et seulement le nécessaire pour « authenticated ».
-- =====================================================================
revoke all on public.profils from anon;
revoke all on public.profils from authenticated;
grant select, insert, update on public.profils to authenticated;
revoke all on public.telechargements from authenticated;
grant select, insert on public.telechargements to authenticated;
revoke all on public.films_facebook from authenticated;
grant select, insert, update, delete on public.films_facebook to authenticated;
revoke all on public.admins from anon, authenticated;
