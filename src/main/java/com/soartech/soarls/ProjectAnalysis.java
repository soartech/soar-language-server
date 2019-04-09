package com.soartech.soarls;

import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.eclipse.lsp4j.Location;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

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
    public final String entryPointUri;

    public final ImmutableMap<String, FileAnalysis> files;

    /** A mapping from procedure names to where they were defined. If
     * a procedure gets defined multiple times, then this shall store
     * the most recent definition of that procedure. */
    public final ImmutableMap<String, ProcedureDefinition> procedureDefinitions;

    /** A mapping from procedure definitions to all their call
     * sites. This is used to find all references. */
    public final ImmutableMap<ProcedureDefinition, ImmutableList<ProcedureCall>> procedureCalls;

    /** A mapping from variable names to where they were defined. */
    public final ImmutableMap<String, VariableDefinition> variableDefinitions;

    /** A mapping from variable definitions to all their retrieval
     * sites. This is used to find all refereces. */
    public final ImmutableMap<VariableDefinition, ImmutableList<VariableRetrieval>> variableRetrievals;

    /** Construct a new ProjectAnalysis, converting all the
     * collections into their immutable counteparts. */
    ProjectAnalysis(
        String entryPointUri,
        Map<String, FileAnalysis> files,
        Map<String, ProcedureDefinition> procedureDefinitions,
        Map<ProcedureDefinition, List<ProcedureCall>> procedureCalls,
        Map<String, VariableDefinition> variableDefinitions,
        Map<VariableDefinition, List<VariableRetrieval>> variableRetrievals) {
        this.entryPointUri = entryPointUri;
        this.files = ImmutableMap.copyOf(files);
        this.procedureDefinitions = ImmutableMap.copyOf(procedureDefinitions);
        this.procedureCalls = procedureCalls.entrySet().stream()
            .collect(toImmutableMap(e -> e.getKey(), e -> ImmutableList.copyOf(e.getValue())));
        this.variableDefinitions = ImmutableMap.copyOf(variableDefinitions);
        this.variableRetrievals = variableRetrievals.entrySet().stream()
            .collect(toImmutableMap(e -> e.getKey(), e -> ImmutableList.copyOf(e.getValue())));
    }
}
