package com.soartech.soarls;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.Location;

/** The results of analysing a Soar project starting from a particular
 * entry point.
 *
 * It is common for a Soar codebase to contain multiple entry points,
 * where each entry point loads a common set of Soar code. However,
 * even though they share code, the shared code is being loaded with
 * respect to a different interpreter state each time. The
 * dramatically limits or eliminates the possibility to share analysis
 * data.
 */
class ProjectAnalysis {
    final String entryPointUri;

    final Map<String, FileAnalysis> files = new HashMap<>();

    /** A mapping from procedure names to where they were defined. If
     * a procedure gets defined multiple times, then this shall store
     * the most recent definition of that procedure. */
    final Map<String, ProcedureDefinition> procedureDefinitions = new HashMap<>();

    /** A mapping from procedure definitions to all their call
     * sites. */
    final Map<ProcedureDefinition, List<ProcedureCall>> procedureCalls = new HashMap<>();

    ProjectAnalysis(String entryPointUri) {
        this.entryPointUri = entryPointUri;
    }

}
