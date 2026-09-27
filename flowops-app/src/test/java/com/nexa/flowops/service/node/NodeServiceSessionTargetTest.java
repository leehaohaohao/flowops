package com.nexa.flowops.service.node;

import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.master.NexaMaster;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NodeServiceSessionTargetTest {

    @Test
    @SuppressWarnings("unchecked")
    void targetKeepsTheSelectedSessionAndGenerationAfterTakeover() {
        SessionManager sessions = new SessionManager();
        SessionTracker tracker = new SessionTracker();
        NexaMaster master = mock(NexaMaster.class);
        when(master.getSessionManager()).thenReturn(sessions);
        ObjectProvider<NexaMaster> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(master);
        NodeService nodes = new NodeService(provider, tracker);

        EmbeddedChannel oldChannel = new EmbeddedChannel();
        RunnerSession old = new RunnerSession("runner-1", oldChannel, "old", "127.0.0.1", "test");
        tracker.bindGeneration(old, tracker.beginSession());
        sessions.register(old);
        NodeService.SessionTarget selected = nodes.getCurrentTarget("runner-1").orElseThrow();

        EmbeddedChannel newChannel = new EmbeddedChannel();
        RunnerSession successor = new RunnerSession("runner-1", newChannel, "new", "127.0.0.1", "test");
        tracker.bindGeneration(successor, tracker.beginSession());
        sessions.register(successor);

        assertEquals(tracker.generationOf(old), selected.generation());
        assertTrue(selected.send(Envelope.newBuilder().build()));
        assertNotNull(oldChannel.readOutbound(), "所选连接应收到消息");
        assertNull(newChannel.readOutbound(), "接管者不应收到归属旧会话的消息");
    }
}
