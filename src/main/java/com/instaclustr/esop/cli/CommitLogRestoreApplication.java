package com.instaclustr.esop.cli;

import static com.instaclustr.picocli.CLIApplication.execute;
import static com.instaclustr.picocli.JarManifestVersionProvider.logCommandVersionInformation;
import static java.util.Collections.singletonList;
import static org.awaitility.Awaitility.await;

import com.google.inject.Inject;
import com.instaclustr.esop.impl.restore.RestoreCommitLogsOperationRequest;
import com.instaclustr.esop.impl.restore.RestoreModules.RestoreCommitlogModule;
import com.instaclustr.operations.Operation;
import com.instaclustr.operations.OperationsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "commitlog-restore",
    description = "Restores archived commit logs to node.",
    sortOptions = false,
    versionProvider = Esop.class,
    mixinStandardHelpOptions = true
)
public class CommitLogRestoreApplication implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CommitLogRestoreApplication.class);

    @Spec
    private CommandSpec spec;

    @Mixin
    private RestoreCommitLogsOperationRequest request;

    @Inject
    private OperationsService operationsService;

    public static void main(String[] args) {
        System.exit(execute(new CommitLogRestoreApplication(), args));
    }

    @Override
    public void run() {
        logCommandVersionInformation(spec);

        Esop.init(this, null, request, logger, singletonList(new RestoreCommitlogModule()));

        final Operation<?> operation = operationsService.submitOperationRequest(request);

        await().forever().until(() -> operation.state.isTerminalState());

        if (operation.state == Operation.State.FAILED) {
            throw new IllegalStateException("Commitlog restore operation was not successful.");
        }
    }
}
