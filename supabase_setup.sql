-- =====================================================================
--  Sossokouamé : base de données (comptes, profils, films Facebook)
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
--         <h2>Sossokouamé</h2>
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

-- =====================================================================
--  5) NUMÉROS DE SÉRIE + CODES (remplace l'envoi d'e-mails ; relançable)
--     - Chaque inscription crée un NOUVEAU numéro de série, lié à ce compte.
--     - Confirmation du compte et mot de passe oublié = e-mail + numéro de série + code.
--     - L'application n'utilise plus /auth/v1/signup ni /auth/v1/recover.
--     - Dans le tableau de bord : Authentication > Sign In / Providers > désactivez
--       « Allow new users to sign up » (l'inscription passe par inscrire_compte).
-- =====================================================================
create table if not exists public.comptes_serie (
  user_id      uuid primary key references auth.users(id) on delete cascade,
  serie        text not null unique,
  confirme     boolean not null default false,
  code         text,
  code_objet   text,
  code_expire  timestamptz,
  essais       int not null default 0,
  bloque_jusqu timestamptz,
  cree_le      timestamptz not null default now()
);
alter table public.comptes_serie enable row level security;
revoke all on public.comptes_serie from anon, authenticated;

create or replace function public._gen_serie() returns text
language plpgsql security definer set search_path = public, extensions as $$
declare alpha text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; b bytea := gen_random_bytes(16); s text := ''; i int;
begin
  for i in 0..15 loop
    s := s || substr(alpha, (get_byte(b, i) % 32) + 1, 1);
    if i in (3, 7, 11) then s := s || '-'; end if;
  end loop;
  return s;
end $$;

create or replace function public._gen_code() returns text
language plpgsql security definer set search_path = public, extensions as $$
declare b bytea := gen_random_bytes(4);
begin
  return lpad((((get_byte(b,0)::bigint * 16777216) + (get_byte(b,1) * 65536) + (get_byte(b,2) * 256) + get_byte(b,3)) % 1000000)::text, 6, '0');
end $$;

create or replace function public._echec(p_uid uuid) returns void
language sql security definer set search_path = public as $$
  update public.comptes_serie
     set bloque_jusqu = case when essais + 1 >= 5 then now() + interval '15 minutes' else bloque_jusqu end,
         essais       = case when essais + 1 >= 5 then 0 else essais + 1 end
   where user_id = p_uid;
$$;

create or replace function public._controle(p_email text, p_serie text, out uid uuid, out erreur text)
language plpgsql security definer set search_path = public, auth, extensions as $$
declare r public.comptes_serie;
begin
  select u.id into uid from auth.users u where lower(u.email) = lower(trim(coalesce(p_email, '')));
  if uid is null then erreur := 'incorrect'; return; end if;
  select * into r from public.comptes_serie where user_id = uid for update;
  if not found then uid := null; erreur := 'incorrect'; return; end if;
  if r.bloque_jusqu is not null and r.bloque_jusqu > now() then uid := null; erreur := 'bloque'; return; end if;
  if replace(replace(upper(coalesce(p_serie, '')), '-', ''), ' ', '') <> replace(r.serie, '-', '') then
    perform public._echec(uid); uid := null; erreur := 'incorrect'; return;
  end if;
end $$;

-- INSCRIPTION : crée le compte (e-mail non confirmé), un numéro de série et un code de confirmation.
create or replace function public.inscrire_compte(p_nom text, p_email text, p_passe text) returns json
language plpgsql security definer set search_path = public, auth, extensions as $$
declare e text := lower(trim(coalesce(p_email, ''))); n text := trim(coalesce(p_nom, ''));
        uid uuid := gen_random_uuid(); v_serie text; v_code text;
begin
  if e !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' or char_length(e) > 254 then return json_build_object('ok', false, 'erreur', 'email_invalide'); end if;
  if char_length(n) < 2 or char_length(n) > 100 then return json_build_object('ok', false, 'erreur', 'nom_invalide'); end if;
  if char_length(coalesce(p_passe, '')) < 6 or char_length(p_passe) > 72 then return json_build_object('ok', false, 'erreur', 'mot_de_passe_faible'); end if;
  if exists (select 1 from auth.users where lower(email) = e) then return json_build_object('ok', false, 'erreur', 'email_existe'); end if;
  if (select count(*) from public.comptes_serie where not confirme and cree_le > now() - interval '1 hour') >= 30 then
    return json_build_object('ok', false, 'erreur', 'trop_de_demandes');
  end if;

  insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
      raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
      confirmation_token, recovery_token, email_change_token_new, email_change,
      email_change_token_current, phone_change, phone_change_token, reauthentication_token)
  values ('00000000-0000-0000-0000-000000000000', uid, 'authenticated', 'authenticated', e,
      crypt(p_passe, gen_salt('bf')), null,
      '{"provider":"email","providers":["email"]}'::jsonb, jsonb_build_object('nom', n), now(), now(),
      '', '', '', '', '', '', '', '');

  insert into auth.identities (id, provider_id, user_id, identity_data, provider, created_at, updated_at)
  values (gen_random_uuid(), uid::text, uid,
      jsonb_build_object('sub', uid::text, 'email', e, 'email_verified', false, 'phone_verified', false),
      'email', now(), now());

  v_serie := public._gen_serie();
  v_code  := public._gen_code();
  insert into public.comptes_serie (user_id, serie, confirme, code, code_objet, code_expire)
  values (uid, v_serie, false, v_code, 'confirmation', now() + interval '1 hour');

  return json_build_object('ok', true, 'serie', v_serie, 'code', v_code);
end $$;

-- DEMANDER UN CODE (confirmation ou réinitialisation) : exige l'e-mail ET le numéro de série.
create or replace function public.demander_code(p_email text, p_serie text, p_objet text) returns json
language plpgsql security definer set search_path = public, auth, extensions as $$
declare c record; r public.comptes_serie; v_code text;
begin
  if p_objet not in ('confirmation', 'reinitialisation') then return json_build_object('ok', false, 'erreur', 'incorrect'); end if;
  select * into c from public._controle(p_email, p_serie);
  if c.erreur is not null then return json_build_object('ok', false, 'erreur', c.erreur); end if;
  select * into r from public.comptes_serie where user_id = c.uid;
  if p_objet = 'confirmation' and r.confirme then return json_build_object('ok', false, 'erreur', 'deja_confirme'); end if;
  if p_objet = 'reinitialisation' and not r.confirme then return json_build_object('ok', false, 'erreur', 'non_confirme'); end if;
  v_code := public._gen_code();
  update public.comptes_serie
     set code = v_code, code_objet = p_objet,
         code_expire = now() + case when p_objet = 'confirmation' then interval '1 hour' else interval '10 minutes' end
   where user_id = c.uid;
  return json_build_object('ok', true, 'code', v_code);
end $$;

-- CONFIRMER LE COMPTE
create or replace function public.confirmer_compte(p_email text, p_serie text, p_code text) returns json
language plpgsql security definer set search_path = public, auth, extensions as $$
declare c record; r public.comptes_serie;
begin
  select * into c from public._controle(p_email, p_serie);
  if c.erreur is not null then return json_build_object('ok', false, 'erreur', c.erreur); end if;
  select * into r from public.comptes_serie where user_id = c.uid;
  if r.confirme then return json_build_object('ok', true); end if;
  if r.code is null or r.code_objet <> 'confirmation' or r.code_expire < now() then
    return json_build_object('ok', false, 'erreur', 'code_expire');
  end if;
  if r.code <> trim(coalesce(p_code, '')) then
    perform public._echec(c.uid);
    return json_build_object('ok', false, 'erreur', 'code_incorrect');
  end if;
  update auth.users set email_confirmed_at = now(), updated_at = now() where id = c.uid;
  update public.comptes_serie set confirme = true, code = null, code_objet = null, code_expire = null, essais = 0 where user_id = c.uid;
  return json_build_object('ok', true);
end $$;

-- MOT DE PASSE OUBLIÉ : e-mail + série + code + nouveau mot de passe
create or replace function public.reinitialiser_mot_de_passe(p_email text, p_serie text, p_code text, p_nouveau text) returns json
language plpgsql security definer set search_path = public, auth, extensions as $$
declare c record; r public.comptes_serie;
begin
  if char_length(coalesce(p_nouveau, '')) < 6 or char_length(p_nouveau) > 72 then return json_build_object('ok', false, 'erreur', 'mot_de_passe_faible'); end if;
  select * into c from public._controle(p_email, p_serie);
  if c.erreur is not null then return json_build_object('ok', false, 'erreur', c.erreur); end if;
  select * into r from public.comptes_serie where user_id = c.uid;
  if r.code is null or r.code_objet <> 'reinitialisation' or r.code_expire < now() then
    return json_build_object('ok', false, 'erreur', 'code_expire');
  end if;
  if r.code <> trim(coalesce(p_code, '')) then
    perform public._echec(c.uid);
    return json_build_object('ok', false, 'erreur', 'code_incorrect');
  end if;
  update auth.users set encrypted_password = crypt(p_nouveau, gen_salt('bf')), updated_at = now() where id = c.uid;
  delete from auth.sessions where user_id = c.uid;
  update public.comptes_serie set code = null, code_objet = null, code_expire = null, essais = 0 where user_id = c.uid;
  return json_build_object('ok', true);
end $$;

-- ADMIN : comptes avec numéro de série, nouvelle série, nouveau mot de passe (réservé à est_admin())
create or replace function public.admin_comptes() returns json
language plpgsql stable security definer set search_path = public, auth as $$
begin
  if not public.est_admin() then raise exception 'reserve a l administrateur'; end if;
  return coalesce((select json_agg(json_build_object('id', p.id, 'nom', p.nom, 'email', p.email, 'serie', s.serie,
           'confirme', s.confirme, 'cree_le', p.cree_le, 'derniere_connexion', p.derniere_connexion) order by p.cree_le)
         from public.profils p left join public.comptes_serie s on s.user_id = p.id), '[]'::json);
end $$;

create or replace function public.admin_nouvelle_serie(p_uid uuid) returns json
language plpgsql security definer set search_path = public, auth, extensions as $$
declare v text := public._gen_serie();
begin
  if not public.est_admin() then raise exception 'reserve a l administrateur'; end if;
  insert into public.comptes_serie (user_id, serie, confirme)
  values (p_uid, v, (select email_confirmed_at is not null from auth.users where id = p_uid))
  on conflict (user_id) do update set serie = excluded.serie, essais = 0, bloque_jusqu = null,
      code = null, code_objet = null, code_expire = null;
  return json_build_object('ok', true, 'serie', v);
end $$;

create or replace function public.admin_changer_mot_de_passe(p_uid uuid, p_nouveau text) returns json
language plpgsql security definer set search_path = public, auth, extensions as $$
begin
  if not public.est_admin() then raise exception 'reserve a l administrateur'; end if;
  if char_length(coalesce(p_nouveau, '')) < 6 or char_length(p_nouveau) > 72 then return json_build_object('ok', false, 'erreur', 'mot_de_passe_faible'); end if;
  update auth.users set encrypted_password = crypt(p_nouveau, gen_salt('bf')), updated_at = now() where id = p_uid;
  delete from auth.sessions where user_id = p_uid;
  return json_build_object('ok', true);
end $$;

-- Droits
revoke all on function public._gen_serie(), public._gen_code(), public._echec(uuid), public._controle(text, text) from public, anon, authenticated;
revoke all on function public.inscrire_compte(text, text, text), public.demander_code(text, text, text),
  public.confirmer_compte(text, text, text), public.reinitialiser_mot_de_passe(text, text, text, text),
  public.admin_comptes(), public.admin_nouvelle_serie(uuid), public.admin_changer_mot_de_passe(uuid, text) from public, anon, authenticated;
grant execute on function public.inscrire_compte(text, text, text), public.demander_code(text, text, text),
  public.confirmer_compte(text, text, text), public.reinitialiser_mot_de_passe(text, text, text, text) to anon, authenticated;
grant execute on function public.admin_comptes(), public.admin_nouvelle_serie(uuid), public.admin_changer_mot_de_passe(uuid, text) to authenticated;

-- Comptes existants : on leur attribue un numéro de série
insert into public.comptes_serie (user_id, serie, confirme)
select id, public._gen_serie(), email_confirmed_at is not null from auth.users
on conflict (user_id) do nothing;

-- =====================================================================
--  6) TV KOUAMÉ : programme de la journée, en direct (relançable)
--     Les vidéos de « films_facebook » (avec leur durée) sont mises bout à bout depuis 00h00
--     (heure du Bénin, UTC+1). À 01h07, l'application lance donc le programme en cours à la bonne minute.
--     Si le total dure moins de 24 h, le programme se répète en boucle ; il repart du début à minuit.
-- =====================================================================
create table if not exists public.tv_programme (
  ordre   int primary key,
  film_id uuid not null references public.films_facebook(id) on delete cascade,
  canal   text not null default 'Kouamé TV'
);
alter table public.tv_programme enable row level security;
revoke all on public.tv_programme from anon, authenticated;

-- Lu par tous les utilisateurs connectés : heure du serveur + programmes dans l'ordre.
create or replace function public.tv_planning() returns json
language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'connexion requise'; end if;
  return json_build_object(
    'maintenant_ms', (extract(epoch from (now() at time zone 'Africa/Porto-Novo')::time) * 1000)::bigint,
    'programmes', coalesce((
      select json_agg(json_build_object('ordre', t.ordre, 'canal', t.canal, 'titre', f.titre, 'duree_ms', f.duree_ms,
                                        'lien', f.lien, 'serie', f.serie, 'episode', f.episode) order by t.ordre)
        from public.tv_programme t join public.films_facebook f on f.id = t.film_id
       where f.duree_ms > 0), '[]'::json));
end $$;

-- Admin : remplit automatiquement la journée (séries rangées par épisode, puis les autres films).
create or replace function public.admin_tv_generer(p_canal text default 'Kouamé TV') returns json
language plpgsql security definer set search_path = public as $$
declare n int; total bigint; v_canal text := left(coalesce(nullif(trim(p_canal), ''), 'Kouamé TV'), 60);
begin
  if not public.est_admin() then raise exception 'reserve a l administrateur'; end if;
  delete from public.tv_programme where true;
  insert into public.tv_programme (ordre, film_id, canal)
  select (row_number() over (order by o))::int, id, v_canal
    from (select id, o, sum(duree_ms) over (order by o) - duree_ms as debut
            from (select id, duree_ms,
                         row_number() over (order by coalesce(serie, '~'), coalesce(episode, 0), cree_le) as o
                    from public.films_facebook where duree_ms > 0) a) b
   where debut < 86400000;
  get diagnostics n = row_count;
  select coalesce(sum(f.duree_ms), 0) into total
    from public.tv_programme t join public.films_facebook f on f.id = t.film_id;
  return json_build_object('ok', true, 'nombre', n, 'duree_ms', total);
end $$;

create or replace function public.admin_tv_vider() returns json
language plpgsql security definer set search_path = public as $$
begin
  if not public.est_admin() then raise exception 'reserve a l administrateur'; end if;
  delete from public.tv_programme where true;
  return json_build_object('ok', true);
end $$;

revoke all on function public.tv_planning(), public.admin_tv_generer(text), public.admin_tv_vider() from public, anon, authenticated;
grant execute on function public.tv_planning(), public.admin_tv_generer(text), public.admin_tv_vider() to authenticated;

-- =====================================================================
--  7) IMAGES ET TITRES MANQUANTS : compléter une vidéo déjà enregistrée SANS image ou SANS titre
--     (avant, un premier partage incomplet bloquait les suivants : « ignore-duplicates »).
--     Seules les colonnes « miniature » et « titre » peuvent être modifiées, et seulement si elles sont encore vides
--     (l'application filtre « est vide » ; la règle ci-dessous empêche aussi les valeurs démesurées).
-- =====================================================================
grant update (miniature, titre) on public.films_facebook to authenticated;

drop policy if exists "completer miniature" on public.films_facebook;
drop policy if exists "completer champs" on public.films_facebook;
create policy "completer champs" on public.films_facebook
    for update to authenticated
    using (miniature is null or titre is null)
    with check ((miniature is null or char_length(miniature) <= 80000)
            and (titre is null or char_length(titre) <= 300));

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
