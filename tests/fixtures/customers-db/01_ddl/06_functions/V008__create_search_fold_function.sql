-- HU-CLI-05: one folding function so every customer search compares text the same way.
-- Lower-cases and removes Latin diacritics (a with acute -> a, n with tilde -> n, u with diaeresis -> u,
-- c with cedilla -> c) and any combining mark, so decomposed (NFD) and precomposed input match.
-- The search path is pinned and no extension is required (the database does not create any).
CREATE FUNCTION customers_schema.fold_search_text(value text)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
STRICT
SET search_path = pg_catalog, pg_temp
AS $fold$
    SELECT lower(translate(
        regexp_replace(value, U&'[\0300-\036F]', '', 'g'),
        U&'\00C1\00C0\00C2\00C4\00C3\00C5\00E1\00E0\00E2\00E4\00E3\00E5\00C9\00C8\00CA\00CB\00E9\00E8\00EA\00EB\00CD\00CC\00CE\00CF\00ED\00EC\00EE\00EF\00D3\00D2\00D4\00D6\00D5\00F3\00F2\00F4\00F6\00F5\00DA\00D9\00DB\00DC\00FA\00F9\00FB\00FC\00DD\00FD\00FF\00D1\00F1\00C7\00E7',
        'AAAAAAaaaaaaEEEEeeeeIIIIiiiiOOOOOoooooUUUUuuuuYyyNnCc'
    ))
$fold$;
