
-- =====================================================================
--  9) JEUX : meilleurs scores partagés (Panda Dash, Super Kouamé, Serpent)
--     À exécuter UNE fois dans Supabase > SQL Editor (relançable sans risque).
--     - Tous les joueurs connectés peuvent LIRE le classement.
--     - Personne ne peut écrire directement dans la table : l'ajout passe par enregistrer_score(),
--       qui ne garde que le MEILLEUR score de chaque joueur pour chaque jeu.
-- =====================================================================
create table if not exists public.scores_jeux (
    utilisateur uuid        not null references auth.users (id) on delete cascade,
    jeu         text        not null,
    nom         text,
    score       integer     not null default 0,
    maj         timestamptz not null default now(),
    primary key (utilisateur, jeu),
    constraint jeu_connu       check (jeu in ('panda', 'mario', 'serpent')),
    constraint score_borne     check (score between 0 and 1000000),
    constraint nom_court_score check (nom is null or char_length(nom) <= 100)
);

create index if not exists scores_jeux_classement on public.scores_jeux (jeu, score desc);

alter table public.scores_jeux enable row level security;

drop policy if exists "scores lecture" on public.scores_jeux;
create policy "scores lecture" on public.scores_jeux
    for select to authenticated using (true);

revoke all on public.scores_jeux from anon, authenticated;
grant select on public.scores_jeux to authenticated;

create or replace function public.enregistrer_score(p_jeu text, p_score integer) returns json
language plpgsql security definer set search_path = public as $$
declare
    v_uid   uuid := auth.uid();
    v_nom   text;
    v_score integer;
begin
    if v_uid is null then raise exception 'connexion requise'; end if;
    if p_jeu is null or p_jeu not in ('panda', 'mario', 'serpent') then raise exception 'jeu inconnu'; end if;
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
