/*
 * Copyright (c) 2010-2018. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.axonserver.connector.command;

import com.google.protobuf.ByteString;
import io.axoniq.axonserver.grpc.MetaDataValue;
import io.axoniq.axonserver.grpc.SerializedObject;
import io.axoniq.axonserver.grpc.command.Command;
import io.axoniq.axonserver.grpc.command.CommandProviderInbound;
import io.axoniq.axonserver.grpc.command.CommandProviderOutbound;
import io.grpc.stub.StreamObserver;
import org.axonframework.axonserver.connector.AxonServerConfiguration;
import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.commandhandling.*;
import org.axonframework.common.Registration;
import org.axonframework.serialization.xml.XStreamSerializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Author: marc
 */
public class AxonServerCommandBusTest {
    private AxonServerCommandBus testSubject;
    private DummyMessagePlatformServer dummyMessagePlatformServer;
    private AxonServerConfiguration conf;
    private XStreamSerializer ser;
    private SimpleCommandBus localSegment;


    @Before
    public void setup() throws Exception {
        conf = new AxonServerConfiguration();
        conf.setServers("localhost:4344");
        conf.setClientName("JUnit");
        conf.setComponentName("JUnit");
        conf.setInitialNrOfPermits(100);
        conf.setNewPermitsThreshold(10);
        conf.setNrOfNewPermits(1000);
        localSegment = SimpleCommandBus.builder().build();
        ser = XStreamSerializer.builder().build();
        testSubject = new AxonServerCommandBus(new AxonServerConnectionManager(conf), conf, localSegment, ser,
                command -> "RoutingKey", new CommandPriorityCalculator() {});
        dummyMessagePlatformServer = new DummyMessagePlatformServer(4344);
        dummyMessagePlatformServer.start();
    }

    @After
    public void tearDown() {
        dummyMessagePlatformServer.stop();
    }

    @Test
    public void dispatch() throws Exception {
        CommandMessage<String> commandMessage = new GenericCommandMessage<>("this is the payload");
        CountDownLatch waiter = new CountDownLatch(1);
        AtomicReference<String> resultHolder = new AtomicReference<>();
        AtomicBoolean failure = new AtomicBoolean(false);
        testSubject.dispatch(commandMessage, (CommandCallback<String, String>) (cm, result) -> {
            if (result.isExceptional()) {
                failure.set(true);
            } else {
                resultHolder.set(result.getPayload());
            }
            waiter.countDown();
        });
        waiter.await();
        assertEquals(resultHolder.get(), "this is the payload");
        assertFalse(failure.get());
    }
    @Test
    public void dispatchWithError() throws Exception {
        CommandMessage<String> commandMessage = new GenericCommandMessage<>("this is an error request");
        CountDownLatch waiter = new CountDownLatch(1);
        AtomicReference<String> resultHolder = new AtomicReference<>();
        AtomicBoolean failure = new AtomicBoolean(false);
        testSubject.dispatch(commandMessage, (CommandCallback<String, String>) (cm, result) -> {
            if (result.isExceptional()) {
                failure.set(true);
            } else {
                resultHolder.set(result.getPayload());
            }
            waiter.countDown();
        });
        waiter.await();
        assertTrue(failure.get());
    }

    @Test
    public void subscribe() throws Exception {
        Registration registration = testSubject.subscribe(String.class.getName(), c -> "Done");
        Thread.sleep(30);
        assertNotNull( dummyMessagePlatformServer.subscriptions(String.class.getName()));
        registration.cancel();
        Thread.sleep(30);
        assertNull( dummyMessagePlatformServer.subscriptions(String.class.getName()));
    }

    @Test
    public void processCommand() {
        AxonServerConnectionManager mockAxonServerConnectionManager = mock(AxonServerConnectionManager.class);
        AtomicReference<StreamObserver<CommandProviderInbound>> inboundStreamObserverRef = new AtomicReference<>();
        doAnswer(invocationOnMock -> {
            inboundStreamObserverRef.set( invocationOnMock.getArgument(0));
            return new StreamObserver<CommandProviderOutbound>() {
                @Override
                public void onNext(CommandProviderOutbound commandProviderOutbound) {
                    System.out.println(commandProviderOutbound);
                }

                @Override
                public void onError(Throwable throwable) {

                }

                @Override
                public void onCompleted() {

                }
            };
        }).when(mockAxonServerConnectionManager).getCommandStream(any(), any());
        AxonServerCommandBus testSubject2 = new AxonServerCommandBus(mockAxonServerConnectionManager, conf, localSegment, ser,
                command -> "RoutingKey", new CommandPriorityCalculator() {});
        testSubject2.subscribe(String.class.getName(), c -> c.getMetaData().get("test1"));

        inboundStreamObserverRef.get().onNext(CommandProviderInbound.newBuilder()
                .setCommand(Command.newBuilder().setName(String.class.getName())
                        .setPayload(SerializedObject.newBuilder()
                        .setType(String.class.getName())
                        .setData(ByteString.copyFromUtf8("<string>test</string>")))
                        .putMetaData("test1", MetaDataValue.newBuilder().setTextValue("Text").build())
                )
                .build());

    }

    @Test
    public void resubscribe() throws Exception {
        testSubject.subscribe(String.class.getName(), c -> "Done");
        Thread.sleep(30);
        assertNotNull( dummyMessagePlatformServer.subscriptions(String.class.getName()));
        dummyMessagePlatformServer.stop();
        assertNull(dummyMessagePlatformServer.subscriptions(String.class.getName()));
        dummyMessagePlatformServer.start();
        Thread.sleep(3000);
        assertNotNull(dummyMessagePlatformServer.subscriptions(String.class.getName()));
    }

    @Test
    public void dispatchInterceptor(){
        List<Object> results = new LinkedList<>();
        testSubject.registerDispatchInterceptor(messages -> (a,b) -> {
            results.add(b.getPayload());
            return b;
        });
        testSubject.dispatch(new GenericCommandMessage<>("payload"));
        assertEquals("payload", results.get(0));
        assertEquals(1, results.size());
    }


    @Test
    public void reconnectAfterConnectionLost() throws InterruptedException {
        testSubject.subscribe(String.class.getName(), c -> "Done");
        Thread.sleep(30);
        assertNotNull(dummyMessagePlatformServer.subscriptions(String.class.getName()));
        dummyMessagePlatformServer.onError(String.class.getName());
        Thread.sleep(200);
        assertNotNull( dummyMessagePlatformServer.subscriptions(String.class.getName()));
    }


}
