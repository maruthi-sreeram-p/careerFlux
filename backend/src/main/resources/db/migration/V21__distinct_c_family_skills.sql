-- ===========================================================================
-- V21  C, C++ and C# as three skills, not one
--
-- Skill slugs dropped every character that was not a letter or a digit, so
-- "C++", "C#" and "C" all became `c`. The dictionary seeds "C#" and "C++" from
-- an unordered map, so whichever came first on a database's first start took
-- `c` and the other was never created. Every database therefore holds one `c`
-- row, named either "C#" or "C++", and anything typed as C, C++ or C# has
-- resolved to it since.
--
-- Skill slugs now spell the symbols out ("c-plus-plus", "c-sharp"). This moves
-- that one row to the slug its own name now produces, which frees `c` for the
-- C language and lets the dictionary seeder create whichever of C#/C++ was
-- never created.
--
-- WHAT THIS DOES NOT DO
--
-- It does not re-point anything already linked to the row. Links made by
-- resolving "C", "C++" or "C#" all landed on it and cannot be told apart now,
-- so they stay where they are rather than being moved on a guess.
--
-- IT CANNOT COLLIDE
--
-- Each update applies only when the row is named exactly "C#" or "C++" and the
-- new slug is free. If another row already owns it, nothing changes and
-- SkillDictionarySeeder warns at start-up, so nothing is merged blindly.
-- ===========================================================================

UPDATE skills
   SET slug = 'c-sharp'
 WHERE slug = 'c'
   AND canonical_name = 'C#'
   AND NOT EXISTS (SELECT 1 FROM skills taken WHERE taken.slug = 'c-sharp');

UPDATE skills
   SET slug = 'c-plus-plus'
 WHERE slug = 'c'
   AND canonical_name = 'C++'
   AND NOT EXISTS (SELECT 1 FROM skills taken WHERE taken.slug = 'c-plus-plus');
