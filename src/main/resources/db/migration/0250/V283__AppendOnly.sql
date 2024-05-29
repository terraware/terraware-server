CREATE FUNCTION reject_delete() RETURNS TRIGGER AS $$
BEGIN
    RAISE 'This table does not allow deletes.';
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION reject_update() RETURNS TRIGGER AS $$
BEGIN
    RAISE 'This table does not allow updates.';
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION reject_delete_value() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM document_producer.pdds WHERE id = OLD.pdd_id) THEN
        RAISE 'This table does not allow deletes.';
    ELSE
        -- The entire PDD is being deleted.
        RETURN OLD;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION reject_update_value() RETURNS TRIGGER AS $$
BEGIN
    IF (OLD.created_by != -1 AND NEW.created_by = -1
        AND NOT EXISTS (SELECT 1 FROM users WHERE id = OLD.created_by))
        OR (OLD.verified_by != -1 AND NEW.verified_by = -1
            AND NOT EXISTS (SELECT 1 FROM users WHERE id = OLD.verified_by))
    THEN
        RAISE 'This table does not allow updates.';
    ELSE
        -- This is an update triggered by a user being deleted.
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION reject_delete_value_child() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM document_producer.variable_values WHERE id = OLD.variable_value_id) THEN
        RAISE 'This table does not allow deletes.';
    ELSE
        RETURN OLD;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER variable_values_no_update
    BEFORE UPDATE ON document_producer.variable_values
    FOR EACH ROW EXECUTE FUNCTION reject_update_value();

CREATE TRIGGER variable_values_no_delete
    BEFORE DELETE ON document_producer.variable_values
    FOR EACH ROW EXECUTE FUNCTION reject_delete_value();

CREATE TRIGGER variable_image_values_no_update
    BEFORE UPDATE ON document_producer.variable_image_values
    FOR EACH ROW EXECUTE FUNCTION reject_update();

CREATE TRIGGER variable_image_values_no_delete
    BEFORE DELETE ON document_producer.variable_image_values
    FOR EACH ROW EXECUTE FUNCTION reject_delete_value_child();

CREATE TRIGGER variable_section_values_no_update
    BEFORE UPDATE ON document_producer.variable_section_values
    FOR EACH ROW EXECUTE FUNCTION reject_update();

CREATE TRIGGER variable_section_values_no_delete
    BEFORE DELETE ON document_producer.variable_section_values
    FOR EACH ROW EXECUTE FUNCTION reject_delete_value_child();

CREATE TRIGGER variable_select_option_values_no_update
    BEFORE UPDATE ON document_producer.variable_select_option_values
    FOR EACH ROW EXECUTE FUNCTION reject_update();

CREATE TRIGGER variable_select_option_values_no_delete
    BEFORE DELETE ON document_producer.variable_select_option_values
    FOR EACH ROW EXECUTE FUNCTION reject_delete_value_child();

CREATE TRIGGER variables_no_update
    BEFORE UPDATE ON document_producer.variables
    FOR EACH ROW EXECUTE FUNCTION reject_update();

CREATE TRIGGER variables_no_delete
    BEFORE DELETE ON document_producer.variables
    FOR EACH ROW EXECUTE FUNCTION reject_delete();
