/*
 * Copyright (c) 2022 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.general;

import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.JOB_ROOT_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.WORKER_OPERATION_NAME;

import datadog.trace.api.Trace;
import io.airbyte.commons.enums.Enums;
import io.airbyte.commons.io.LineGobbler;
import io.airbyte.commons.json.Jsons;
import io.airbyte.config.ConnectorJobOutput;
import io.airbyte.config.ConnectorJobOutput.OutputType;
import io.airbyte.config.FailureReason;
import io.airbyte.config.StandardCheckConnectionInput;
import io.airbyte.config.StandardCheckConnectionOutput;
import io.airbyte.config.StandardCheckConnectionOutput.Status;
import io.airbyte.metrics.lib.ApmTraceUtils;
import io.airbyte.protocol.models.AirbyteConnectionStatus;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.AirbyteMessage.Type;
import io.airbyte.workers.WorkerConstants;
import io.airbyte.workers.WorkerUtils;
import io.airbyte.workers.exception.WorkerException;
import io.airbyte.workers.internal.AirbyteStreamFactory;
import io.airbyte.workers.internal.DefaultAirbyteStreamFactory;
import io.airbyte.workers.process.IntegrationLauncher;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultCheckConnectionWorker implements CheckConnectionWorker {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultCheckConnectionWorker.class);

  private final IntegrationLauncher integrationLauncher;
  private final AirbyteStreamFactory streamFactory;

  private Process process;

  public DefaultCheckConnectionWorker(final IntegrationLauncher integrationLauncher,
                                      final AirbyteStreamFactory streamFactory) {
    this.integrationLauncher = integrationLauncher;
    this.streamFactory = streamFactory;
  }

  public DefaultCheckConnectionWorker(final IntegrationLauncher integrationLauncher) {
    this(integrationLauncher, new DefaultAirbyteStreamFactory());
  }

  @Trace(operationName = WORKER_OPERATION_NAME)
  @Override
  public ConnectorJobOutput run(final StandardCheckConnectionInput input, final Path jobRoot) throws WorkerException {
    LineGobbler.startSection("CHECK");
    ApmTraceUtils.addTagsToTrace(Map.of(JOB_ROOT_KEY, jobRoot));
    try {
      process = integrationLauncher.check(
          jobRoot,
          WorkerConstants.SOURCE_CONFIG_JSON_FILENAME,
          Jsons.serialize(input.getConnectionConfiguration()));
      final int exitCode = process.exitValue();
      LOGGER.debug("Check connection job subprocess finished with exit code {}", exitCode);
      final ConnectorJobOutput jobOutput = new ConnectorJobOutput().withOutputType(OutputType.CHECK_CONNECTION);

      LineGobbler.gobble(process.getErrorStream(), LOGGER::error);

      final Map<Type, List<AirbyteMessage>> messagesByType = WorkerUtils.getMessagesByType(process, streamFactory, 30);
      final Optional<AirbyteConnectionStatus> connectionStatus = messagesByType
          .getOrDefault(Type.CONNECTION_STATUS, new ArrayList<>()).stream()
          .map(AirbyteMessage::getConnectionStatus)
          .findFirst();

      final Optional<FailureReason> failureReason = WorkerUtils.getJobFailureReasonFromMessages(OutputType.CHECK_CONNECTION, messagesByType);
      failureReason.ifPresent(jobOutput::setFailureReason);

      if (connectionStatus.isPresent()) {
        final StandardCheckConnectionOutput output = new StandardCheckConnectionOutput()
            .withStatus(Enums.convertTo(connectionStatus.get().getStatus(), Status.class))
            .withMessage(connectionStatus.get().getMessage());
        LOGGER.debug("Check connection job received output: {}", output);
        jobOutput.setCheckConnection(output);
      } else {
        if (failureReason.isEmpty()) {
          throw new WorkerException("Error checking connection status: no status nor failure reason were outputted.");
        }
      }
      return jobOutput;

    } catch (final Exception e) {
      ApmTraceUtils.addExceptionToTrace(e);
      LOGGER.error("Unexpected error while checking connection: ", e);
      throw new WorkerException("Unexpected error while getting checking connection.", e);
    }
  }

  @Trace(operationName = WORKER_OPERATION_NAME)
  @Override
  public void cancel() {
    WorkerUtils.cancelProcess(process);
  }

}
