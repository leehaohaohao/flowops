package com.nexa.flowops.service.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployRecordMapper;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.service.generate.ConfigGeneratorChain;
import com.nexa.flowops.service.node.NodeService;
import com.nexa.flowops.service.node.PendingTask;
import com.nexa.flowops.service.node.RemoteTaskManager;
import com.nexa.protocol.master.RunnerSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import org.mockito.ArgumentCaptor;

class RemoteDeployDispatcherSessionTest {

    @TempDir Path volumeDir;

    @Test
    void pendingTaskUsesTheSessionThatReceivedTheRequest() {
        DeployServiceMapper services = mock(DeployServiceMapper.class);
        DeployRecordMapper records = mock(DeployRecordMapper.class);
        ConfigGeneratorChain generators = mock(ConfigGeneratorChain.class);
        NodeService nodes = mock(NodeService.class);
        RemoteTaskManager tasks = mock(RemoteTaskManager.class);
        RunnerSession receiver = mock(RunnerSession.class);
        when(receiver.send(any())).thenReturn(true);
        when(nodes.getCurrentTarget("runner-1"))
                .thenReturn(Optional.of(new NodeService.SessionTarget(receiver, 7L)));
        when(nodes.withRunnerLock(eq("runner-1"), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        RemoteDeployDispatcher dispatcher = new RemoteDeployDispatcher(
                services, records, new ObjectMapper(), generators, nodes, tasks);

        DeployService service = new DeployService();
        service.setId(1001L);
        service.setName("test");
        service.setDeployName("test");
        service.setVolumeDir(volumeDir.toString());
        service.setServiceType("backend");
        DeployRecord record = new DeployRecord();
        record.setId(2001L);
        record.setLogPath(volumeDir.resolve("deploy.log").toString());

        dispatcher.dispatch(service, "runner-1", "START", "running", record);

        verify(receiver).send(any());
        verify(nodes, times(1)).getCurrentTarget("runner-1");
        ArgumentCaptor<PendingTask> pending = ArgumentCaptor.forClass(PendingTask.class);
        verify(tasks).register(pending.capture());
        assertEquals(7L, pending.getValue().sessionGeneration());
    }
}
