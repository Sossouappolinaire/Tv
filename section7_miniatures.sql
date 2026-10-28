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
