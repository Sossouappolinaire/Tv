-- =====================================================================
--  11) MOTARD COMBAT : duels en ligne entre deux joueurs (relançable sans risque)
--      À exécuter UNE fois : Supabase > SQL Editor > New query > coller > Run
--      (après supabase_setup.sql et section9_scores_jeux.sql)
--
--      - joueurs_en_ligne()  : les comptes actifs depuis moins de 3 minutes
--      - duel_inviter()      : « Voulez-vous jouer avec moi ? »
--      - duel_invitations()  : mes invitations en attente (lues par l'appli → notification)
--      - duel_repondre()     : Oui / Non
--      - duel_choisir()      : choix du personnage ; quand les deux ont choisi, le combat démarre
--      - duel_sync()         : envoie ma position, reçoit celle de l'adversaire ; les points de vie sont gardés ICI
--      - duel_quitter()      : abandon (l'adversaire gagne)
-- =====================================================================

-- ---------- Tables (lecture seule pour les joueurs ; tout passe par les fonctions ci-dessous) ----------
create table if not exists public.duels_motard (
    id         uuid primary key default gen_random_uuid(),
    hote       uuid        not null references auth.users (id) on delete cascade,
    invite     uuid        not null references auth.users (id) on delete cascade,
    hote_nom   text,
    invite_nom text,
    etat       text        not null default 'invitation',
    debut      timestamptz,
    gagnant    uuid,
    cree       timestamptz not null default now(),
    constraint etat_connu check (etat in ('invitation', 'accepte', 'en_cours', 'refuse', 'annule', 'termine')),
    constraint pas_soi_meme check (hote <> invite)
);

create table if not exists public.duel_etats (
    duel        uuid        not null references public.duels_motard (id) on delete cascade,
    joueur      uuid        not null references auth.users (id) on delete cascade,
    perso       integer,
    pv          integer     not null default 100,
    x           real        not null default 0,
    y           real        not null default 0,
    face        integer     not null default 1,
    act         integer     not null default 0,
    vu          timestamptz not null default now(),
    dernier_coup timestamptz not null default 'epoch',
    primary key (duel, joueur)
);

create index if not exists duels_motard_invite on public.duels_motard (invite, etat, cree desc);

alter table public.duels_motard enable row level security;
alter table public.duel_etats   enable row level security;

drop policy if exists "duel lecture" on public.duels_motard;
create policy "duel lecture" on public.duels_motard
    for select to authenticated using (auth.uid() in (hote, invite));

drop policy if exists "duel etat lecture" on public.duel_etats;
create policy "duel etat lecture" on public.duel_etats
    for select to authenticated using (auth.uid() = joueur or exists (
        select 1 from public.duels_motard d where d.id = duel and auth.uid() in (d.hote, d.invite)));

revoke all on public.duels_motard from anon, authenticated;
revoke all on public.duel_etats   from anon, authenticated;
grant select on public.duels_motard to authenticated;
grant select on public.duel_etats   to authenticated;

-- ---------- Joueurs en ligne ----------
create or replace function public.joueurs_en_ligne() returns json
language plpgsql security definer set search_path = public as $$
begin
    if auth.uid() is null then raise exception 'connexion requise'; end if;
    return coalesce((select json_agg(json_build_object('id', p.id, 'nom', coalesce(nullif(btrim(p.nom), ''), 'Joueur'))
                                     order by p.derniere_activite desc)
                       from (select id, nom, derniere_activite from public.profils
                              where id <> auth.uid() and derniere_activite > now() - interval '3 minutes'
                              order by derniere_activite desc limit 30) p), '[]'::json);
end $$;

-- ---------- Inviter ----------
create or replace function public.duel_inviter(p_invite uuid) returns json
language plpgsql security definer set search_path = public as $$
declare
    v_uid uuid := auth.uid();
    v_nom text;
    v_nom2 text;
    v_id uuid;
begin
    if v_uid is null then raise exception 'connexion requise'; end if;
    if p_invite is null or p_invite = v_uid then raise exception 'joueur invalide'; end if;
    select coalesce(nullif(btrim(nom), ''), 'Joueur') into v_nom  from public.profils where id = v_uid;
    select coalesce(nullif(btrim(nom), ''), 'Joueur') into v_nom2 from public.profils
        where id = p_invite and derniere_activite > now() - interval '3 minutes';
    if v_nom2 is null then raise exception 'ce joueur n''est plus en ligne'; end if;

    -- mes anciennes invitations / duels non terminés sont annulés
    update public.duels_motard set etat = 'annule'
        where hote = v_uid and etat in ('invitation', 'accepte');

    insert into public.duels_motard (hote, invite, hote_nom, invite_nom)
        values (v_uid, p_invite, v_nom, v_nom2) returning id into v_id;
    insert into public.duel_etats (duel, joueur) values (v_id, v_uid), (v_id, p_invite);
    return json_build_object('id', v_id);
end $$;

-- ---------- Mes invitations en attente (moins de 2 minutes) ----------
create or replace function public.duel_invitations() returns json
language plpgsql security definer set search_path = public as $$
begin
    if auth.uid() is null then raise exception 'connexion requise'; end if;
    return coalesce((select json_agg(json_build_object('id', id, 'de', hote_nom) order by cree desc)
                       from public.duels_motard
                      where invite = auth.uid() and etat = 'invitation' and cree > now() - interval '2 minutes'), '[]'::json);
end $$;

-- ---------- Répondre ----------
create or replace function public.duel_repondre(p_duel uuid, p_oui boolean) returns json
language plpgsql security definer set search_path = public as $$
declare v_etat text;
begin
    if auth.uid() is null then raise exception 'connexion requise'; end if;
    update public.duels_motard
       set etat = case when p_oui then 'accepte' else 'refuse' end
     where id = p_duel and invite = auth.uid() and etat = 'invitation' and cree > now() - interval '2 minutes'
     returning etat into v_etat;
    return json_build_object('etat', coalesce(v_etat, 'expire'));
end $$;

-- ---------- Choisir son personnage ----------
create or replace function public.duel_choisir(p_duel uuid, p_perso integer) returns json
language plpgsql security definer set search_path = public as $$
declare v_uid uuid := auth.uid(); v_nb integer;
begin
    if v_uid is null then raise exception 'connexion requise'; end if;
    if p_perso is null or p_perso not between 0 and 3 then raise exception 'personnage inconnu'; end if;
    if not exists (select 1 from public.duels_motard where id = p_duel and v_uid in (hote, invite)
                   and etat in ('accepte', 'en_cours')) then
        raise exception 'duel introuvable';
    end if;
    update public.duel_etats set perso = p_perso, vu = now() where duel = p_duel and joueur = v_uid;
    select count(*) into v_nb from public.duel_etats where duel = p_duel and perso is not null;
    if v_nb = 2 then
        update public.duels_motard set etat = 'en_cours', debut = now() + interval '4 seconds'
            where id = p_duel and etat = 'accepte';
        update public.duel_etats set vu = now(), pv = 100 where duel = p_duel;
    end if;
    return json_build_object('ok', true);
end $$;

-- ---------- Infos du duel (attente de l'invité / de l'adversaire) ----------
create or replace function public.duel_info(p_duel uuid) returns json
language plpgsql security definer set search_path = public as $$
declare d public.duels_motard; v_uid uuid := auth.uid(); v_adv uuid; v_perso integer; v_mon integer;
begin
    if v_uid is null then raise exception 'connexion requise'; end if;
    select * into d from public.duels_motard where id = p_duel and v_uid in (hote, invite);
    if not found then return json_build_object('etat', 'introuvable'); end if;
    v_adv := case when d.hote = v_uid then d.invite else d.hote end;
    select perso into v_perso from public.duel_etats where duel = p_duel and joueur = v_adv;
    select perso into v_mon   from public.duel_etats where duel = p_duel and joueur = v_uid;
    return json_build_object('etat', d.etat, 'adv_nom', case when d.hote = v_uid then d.invite_nom else d.hote_nom end,
                             'adv_perso', v_perso, 'mon_perso', v_mon, 'hote', d.hote = v_uid,
                             'cree_il_y_a', extract(epoch from (now() - d.cree)));
end $$;

-- ---------- Synchronisation pendant le combat (appelée ~6 fois par seconde par chaque joueur) ----------
create or replace function public.duel_sync(p_duel uuid, p_x real, p_y real, p_face integer, p_act integer, p_degats integer)
returns json
language plpgsql security definer set search_path = public as $$
declare
    d public.duels_motard;
    v_uid uuid := auth.uid();
    v_adv uuid;
    moi public.duel_etats;
    adv public.duel_etats;
    v_deg integer := least(greatest(coalesce(p_degats, 0), 0), 16);
begin
    if v_uid is null then raise exception 'connexion requise'; end if;
    select * into d from public.duels_motard where id = p_duel and v_uid in (hote, invite) for update;
    if not found then return json_build_object('etat', 'introuvable'); end if;
    v_adv := case when d.hote = v_uid then d.invite else d.hote end;

    update public.duel_etats
       set x = coalesce(p_x, x), y = coalesce(p_y, y), face = coalesce(p_face, face), act = coalesce(p_act, act), vu = now()
     where duel = p_duel and joueur = v_uid;

    if d.etat = 'en_cours' and now() >= d.debut then
        select * into moi from public.duel_etats where duel = p_duel and joueur = v_uid;
        select * into adv from public.duel_etats where duel = p_duel and joueur = v_adv;

        -- coup porté : au plus un coup toutes les 0,3 seconde par joueur
        if v_deg > 0 and moi.pv > 0 and adv.pv > 0 and now() - moi.dernier_coup > interval '300 milliseconds' then
            update public.duel_etats set pv = greatest(pv - v_deg, 0) where duel = p_duel and joueur = v_adv;
            update public.duel_etats set dernier_coup = now() where duel = p_duel and joueur = v_uid;
        end if;

        select * into moi from public.duel_etats where duel = p_duel and joueur = v_uid;
        select * into adv from public.duel_etats where duel = p_duel and joueur = v_adv;

        if adv.pv <= 0 then
            update public.duels_motard set etat = 'termine', gagnant = v_uid where id = p_duel;
        elsif moi.pv <= 0 then
            update public.duels_motard set etat = 'termine', gagnant = v_adv where id = p_duel;
        elsif adv.vu < now() - interval '12 seconds' then
            -- l'adversaire ne répond plus : victoire par abandon
            update public.duels_motard set etat = 'termine', gagnant = v_uid where id = p_duel;
        end if;
    end if;

    select * into d from public.duels_motard where id = p_duel;
    select * into moi from public.duel_etats where duel = p_duel and joueur = v_uid;
    select * into adv from public.duel_etats where duel = p_duel and joueur = v_adv;

    return json_build_object(
        'etat', d.etat,
        'debut_dans_ms', greatest(0, (extract(epoch from (coalesce(d.debut, now()) - now())) * 1000)::integer),
        'gagnant_moi', d.gagnant is not null and d.gagnant = v_uid,
        'mon_pv', moi.pv,
        'adv', json_build_object('perso', adv.perso, 'pv', adv.pv, 'x', adv.x, 'y', adv.y, 'face', adv.face, 'act', adv.act));
end $$;

-- ---------- Quitter ----------
create or replace function public.duel_quitter(p_duel uuid) returns json
language plpgsql security definer set search_path = public as $$
declare d public.duels_motard; v_uid uuid := auth.uid();
begin
    if v_uid is null then raise exception 'connexion requise'; end if;
    select * into d from public.duels_motard where id = p_duel and v_uid in (hote, invite) for update;
    if not found then return json_build_object('ok', false); end if;
    if d.etat = 'en_cours' then
        update public.duels_motard set etat = 'termine',
            gagnant = case when d.hote = v_uid then d.invite else d.hote end where id = p_duel;
    elsif d.etat in ('invitation', 'accepte') then
        update public.duels_motard set etat = 'annule' where id = p_duel;
    end if;
    return json_build_object('ok', true);
end $$;

-- ---------- Droits : seuls les comptes connectés appellent ces fonctions ----------
revoke all on function public.joueurs_en_ligne()                                              from public, anon, authenticated;
revoke all on function public.duel_inviter(uuid)                                              from public, anon, authenticated;
revoke all on function public.duel_invitations()                                              from public, anon, authenticated;
revoke all on function public.duel_repondre(uuid, boolean)                                    from public, anon, authenticated;
revoke all on function public.duel_choisir(uuid, integer)                                     from public, anon, authenticated;
revoke all on function public.duel_info(uuid)                                                 from public, anon, authenticated;
revoke all on function public.duel_sync(uuid, real, real, integer, integer, integer)          from public, anon, authenticated;
revoke all on function public.duel_quitter(uuid)                                              from public, anon, authenticated;
grant execute on function public.joueurs_en_ligne()                                           to authenticated;
grant execute on function public.duel_inviter(uuid)                                           to authenticated;
grant execute on function public.duel_invitations()                                           to authenticated;
grant execute on function public.duel_repondre(uuid, boolean)                                 to authenticated;
grant execute on function public.duel_choisir(uuid, integer)                                  to authenticated;
grant execute on function public.duel_info(uuid)                                              to authenticated;
grant execute on function public.duel_sync(uuid, real, real, integer, integer, integer)       to authenticated;
grant execute on function public.duel_quitter(uuid)                                           to authenticated;

-- ---------- Classement : ajoute le jeu « motard » à la liste des jeux connus ----------
alter table public.scores_jeux drop constraint if exists jeu_connu;
alter table public.scores_jeux add constraint jeu_connu
    check (jeu in ('panda', 'mario', 'serpent', 'commando', 'liquide', 'motard'));

create or replace function public.enregistrer_score(p_jeu text, p_score integer) returns json
language plpgsql security definer set search_path = public as $$
declare
    v_uid   uuid := auth.uid();
    v_nom   text;
    v_score integer;
begin
    if v_uid is null then raise exception 'connexion requise'; end if;
    if p_jeu is null or p_jeu not in ('panda', 'mario', 'serpent', 'commando', 'liquide', 'motard') then raise exception 'jeu inconnu'; end if;
    v_score := least(greatest(coalesce(p_score, 0), 0), 1000000);
    select nullif(btrim(nom), '') into v_nom from public.profils where id = v_uid;

    insert into public.scores_jeux (utilisateur, jeu, nom, score, maj)
    values (v_uid, p_jeu, coalesce(v_nom, 'Joueur'), v_score, now())
    on conflict (utilisateur, jeu) do update
        set nom   = excluded.nom,
            score = greatest(public.scores_jeux.score, excluded.score),
            maj   = case when excluded.score > public.scores_jeux.score then now() else public.scores_jeux.maj end;

    return json_build_object('ok', true);
end $$;

revoke all on function public.enregistrer_score(text, integer) from public, anon, authenticated;
grant execute on function public.enregistrer_score(text, integer) to authenticated;
