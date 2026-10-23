ALTER TABLE event ADD COLUMN slug VARCHAR(200);

-- Allocate once in PostgreSQL so concurrent creators and all app instances share one namespace.
CREATE FUNCTION assign_event_slug() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE base TEXT; candidate TEXT;
BEGIN
  IF TG_OP='UPDATE' AND OLD.slug IS NOT NULL THEN
    IF NEW.slug IS DISTINCT FROM OLD.slug THEN
      RAISE EXCEPTION 'Published event links are stable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;
  base := trim(both '-' from regexp_replace(translate(lower(NEW.name || CASE WHEN trim(NEW.city)<>'' THEN '-'||NEW.city ELSE '' END),
    'áàâäãåéèêëíìîïóòôöõúùûüñç','aaaaaaeeeeiiiiooooouuuunc'),'[^a-z0-9]+','-','g'));
  base := trim(trailing '-' from left(base,160));
  IF base='' THEN base := 'event'; END IF;
  PERFORM pg_advisory_xact_lock(1414090059,1);
  candidate := base;
  WHILE EXISTS(SELECT 1 FROM event WHERE slug=candidate AND id IS DISTINCT FROM NEW.id) LOOP
    candidate := base || '-' || left(replace(gen_random_uuid()::text,'-',''),8);
  END LOOP;
  NEW.slug := candidate;
  RETURN NEW;
END;
$$;
CREATE TRIGGER event_slug_assignment BEFORE INSERT OR UPDATE OF slug ON event FOR EACH ROW EXECUTE FUNCTION assign_event_slug();
UPDATE event SET slug=NULL;
ALTER TABLE event ALTER COLUMN slug SET NOT NULL;
ALTER TABLE event ADD CONSTRAINT uq_event_slug UNIQUE(slug);
ALTER TABLE event ADD CONSTRAINT ck_event_slug CHECK(slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$');
