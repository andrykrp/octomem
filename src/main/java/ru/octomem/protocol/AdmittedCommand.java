package ru.octomem.protocol;
import ru.octomem.engine.StorageAdmission;
/** Additive decoder-owned native admission capability; command release cancels it.
 * @since 1.0
 */
public interface AdmittedCommand extends Command {
    /** Returns borrowed admission ownership, or null on legacy/unrelated commands. */
    StorageAdmission.Token admission();
}
