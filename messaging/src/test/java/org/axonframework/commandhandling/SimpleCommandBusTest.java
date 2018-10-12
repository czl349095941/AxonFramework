/*
 * Copyright (c) 2010-2018. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.commandhandling;

import org.axonframework.common.Registration;
import org.axonframework.messaging.InterceptorChain;
import org.axonframework.messaging.MessageHandler;
import org.axonframework.messaging.MessageHandlerInterceptor;
import org.axonframework.messaging.correlation.MessageOriginProvider;
import org.axonframework.messaging.interceptors.CorrelationDataInterceptor;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.RollbackConfigurationType;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.axonframework.monitoring.MessageMonitor;
import org.junit.*;
import org.mockito.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.axonframework.commandhandling.GenericCommandMessage.asCommandMessage;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * @author Allard Buijze
 */
public class SimpleCommandBusTest {

    private SimpleCommandBus testSubject;

    @Before
    public void setUp() {
        this.testSubject = SimpleCommandBus.builder().build();
    }

    @After
    public void tearDown() {
        while (CurrentUnitOfWork.isStarted()) {
            CurrentUnitOfWork.get().rollback();
        }
    }

    @Test
    public void testDispatchCommand_HandlerSubscribed() {
        testSubject.subscribe(String.class.getName(), new MyStringCommandHandler());
        testSubject.dispatch(asCommandMessage("Say hi!"),
                             new CommandCallback<String, CommandMessage<String>>() {
                                 @Override
                                 public void onSuccess(CommandMessage<? extends String> command,
                                                       CommandResultMessage<? extends CommandMessage<String>> commandResultMessage) {
                                     assertEquals("Say hi!", commandResultMessage.getPayload().getPayload());
                                 }

                                 @Override
                                 public void onFailure(CommandMessage<? extends String> commandMessage,
                                                       Throwable cause) {
                                     cause.printStackTrace();
                                     fail("Did not expect exception");
                                 }
                             });
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testDispatchCommand_ImplicitUnitOfWorkIsCommittedOnReturnValue() {
        final AtomicReference<UnitOfWork<?>> unitOfWork = new AtomicReference<>();
        testSubject.subscribe(String.class.getName(), command -> {
            unitOfWork.set(CurrentUnitOfWork.get());
            assertTrue(CurrentUnitOfWork.isStarted());
            assertNotNull(CurrentUnitOfWork.get());
            return command;
        });
        testSubject.dispatch(asCommandMessage("Say hi!"),
                             new CommandCallback<String, CommandMessage<String>>() {
                                 @Override
                                 public void onSuccess(CommandMessage<? extends String> commandMessage,
                                                       CommandResultMessage<? extends CommandMessage<String>> commandResultMessage) {
                                     assertEquals("Say hi!", commandResultMessage.getPayload().getPayload());
                                 }

                                 @Override
                                 public void onFailure(CommandMessage<? extends String> commandMessage,
                                                       Throwable cause) {
                                     fail("Did not expect exception");
                                 }
                             });
        assertFalse(CurrentUnitOfWork.isStarted());
        assertFalse(unitOfWork.get().isRolledBack());
        assertFalse(unitOfWork.get().isActive());
    }

    @Test
    public void testDispatchCommand_ImplicitUnitOfWorkIsRolledBackOnException() {
        final AtomicReference<UnitOfWork<?>> unitOfWork = new AtomicReference<>();
        testSubject.subscribe(String.class.getName(), command -> {
            unitOfWork.set(CurrentUnitOfWork.get());
            assertTrue(CurrentUnitOfWork.isStarted());
            assertNotNull(CurrentUnitOfWork.get());
            throw new RuntimeException();
        });
        testSubject.dispatch(asCommandMessage("Say hi!"), new CommandCallback<Object, Object>() {
            @Override
            public void onSuccess(CommandMessage<?> commandMessage, CommandResultMessage<?> commandResultMessage) {
                fail("Expected exception");
            }

            @Override
            public void onFailure(CommandMessage<?> commandMessage, Throwable cause) {
                assertEquals(RuntimeException.class, cause.getClass());
            }
        });
        assertFalse(CurrentUnitOfWork.isStarted());
        assertTrue(unitOfWork.get().isRolledBack());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testDispatchCommand_UnitOfWorkIsCommittedOnCheckedException() {
        final AtomicReference<UnitOfWork<?>> unitOfWork = new AtomicReference<>();
        testSubject.subscribe(String.class.getName(), command -> {
            unitOfWork.set(CurrentUnitOfWork.get());
            throw new Exception();
        });
        testSubject.setRollbackConfiguration(RollbackConfigurationType.UNCHECKED_EXCEPTIONS);

        testSubject.dispatch(asCommandMessage("Say hi!"), new CommandCallback<Object, Object>() {
            @Override
            public void onSuccess(CommandMessage<?> commandMessage, CommandResultMessage<?> commandResultMessage) {
                fail("Expected exception");
            }

            @Override
            public void onFailure(CommandMessage<?> commandMessage, Throwable cause) {
                assertEquals(cause.getClass(), Exception.class);
            }
        });
        assertTrue(!unitOfWork.get().isActive());
        assertTrue(!unitOfWork.get().isRolledBack());
    }


    @SuppressWarnings("unchecked")
    @Test(expected = NoHandlerForCommandException.class)
    public void testDispatchCommand_NoHandlerSubscribed() {
        testSubject.dispatch(asCommandMessage("test"), mock(CommandCallback.class));
    }

    @SuppressWarnings("unchecked")
    @Test(expected = NoHandlerForCommandException.class)
    public void testDispatchCommand_HandlerUnsubscribed() {
        MyStringCommandHandler commandHandler = new MyStringCommandHandler();
        Registration subscription = testSubject.subscribe(String.class.getName(), commandHandler);
        subscription.close();
        testSubject.dispatch(asCommandMessage("Say hi!"), mock(CommandCallback.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testDispatchCommand_NoHandlerSubscribedCallsMonitorCallbackIgnored() throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        MessageMonitor<? super CommandMessage<?>> messageMonitor = (message) -> new MessageMonitor.MonitorCallback() {
            @Override
            public void reportSuccess() {
                fail("Expected #reportFailure");
            }

            @Override
            public void reportFailure(Throwable cause) {
                countDownLatch.countDown();
            }

            @Override
            public void reportIgnored() {
                fail("Expected #reportFailure");
            }
        };

        testSubject = SimpleCommandBus.builder().messageMonitor(messageMonitor).build();

        try {
            testSubject.dispatch(asCommandMessage("test"), mock(CommandCallback.class));
        } catch (NoHandlerForCommandException expected) {
            // ignore
        }

        assertTrue(countDownLatch.await(10, TimeUnit.SECONDS));
    }

    @SuppressWarnings({"unchecked"})
    @Test
    public void testInterceptorChain_CommandHandledSuccessfully() throws Exception {
        MessageHandlerInterceptor<CommandMessage<?>> mockInterceptor1 = mock(MessageHandlerInterceptor.class);
        final MessageHandlerInterceptor<CommandMessage<?>> mockInterceptor2 = mock(MessageHandlerInterceptor.class);
        final MessageHandler<CommandMessage<?>> commandHandler = mock(MessageHandler.class);
        when(mockInterceptor1.handle(isA(UnitOfWork.class), isA(InterceptorChain.class)))
                .thenAnswer(invocation -> mockInterceptor2.handle(
                        (UnitOfWork<CommandMessage<?>>) invocation.getArguments()[0],
                        (InterceptorChain) invocation.getArguments()[1]));
        when(mockInterceptor2.handle(isA(UnitOfWork.class), isA(InterceptorChain.class)))
                .thenAnswer(invocation -> commandHandler
                        .handle(((UnitOfWork<CommandMessage<?>>) invocation.getArguments()[0]).getMessage()));
        testSubject.registerHandlerInterceptor(mockInterceptor1);
        testSubject.registerHandlerInterceptor(mockInterceptor2);
        when(commandHandler.handle(isA(CommandMessage.class))).thenReturn("Hi there!");
        testSubject.subscribe(String.class.getName(), commandHandler);

        testSubject.dispatch(asCommandMessage("Hi there!"),
                             new CommandCallback<Object, Object>() {
                                 @Override
                                 public void onSuccess(CommandMessage<?> commandMessage,
                                                       CommandResultMessage<?> commandResultMessage) {
                                     assertEquals("Hi there!", commandResultMessage.getPayload());
                                 }

                                 @Override
                                 public void onFailure(CommandMessage<?> commandMessage, Throwable cause) {
                                     throw new RuntimeException("Unexpected exception", cause);
                                 }
                             });

        InOrder inOrder = inOrder(mockInterceptor1, mockInterceptor2, commandHandler);
        inOrder.verify(mockInterceptor1).handle(
                isA(UnitOfWork.class), isA(InterceptorChain.class));
        inOrder.verify(mockInterceptor2).handle(
                isA(UnitOfWork.class), isA(InterceptorChain.class));
        inOrder.verify(commandHandler).handle(isA(GenericCommandMessage.class));
    }

    @SuppressWarnings({"unchecked", "ThrowableInstanceNeverThrown"})
    @Test
    public void testInterceptorChain_CommandHandlerThrowsException() throws Exception {
        MessageHandlerInterceptor<CommandMessage<?>> mockInterceptor1 = mock(MessageHandlerInterceptor.class);
        final MessageHandlerInterceptor<CommandMessage<?>> mockInterceptor2 = mock(MessageHandlerInterceptor.class);
        final MessageHandler<CommandMessage<?>> commandHandler = mock(MessageHandler.class);
        when(mockInterceptor1.handle(isA(UnitOfWork.class), isA(InterceptorChain.class)))
                .thenAnswer(invocation -> mockInterceptor2.handle(
                        (UnitOfWork<CommandMessage<?>>) invocation.getArguments()[0],
                        (InterceptorChain) invocation.getArguments()[1]));
        when(mockInterceptor2.handle(isA(UnitOfWork.class), isA(InterceptorChain.class)))
                .thenAnswer(invocation -> commandHandler
                        .handle(((UnitOfWork<CommandMessage<?>>) invocation.getArguments()[0]).getMessage()));

        testSubject.registerHandlerInterceptor(mockInterceptor1);
        testSubject.registerHandlerInterceptor(mockInterceptor2);
        when(commandHandler.handle(isA(CommandMessage.class)))
                .thenThrow(new RuntimeException("Faking failed command handling"));
        testSubject.subscribe(String.class.getName(), commandHandler);

        testSubject.dispatch(asCommandMessage("Hi there!"),
                             new CommandCallback<Object, Object>() {
                                 @Override
                                 public void onSuccess(CommandMessage<?> commandMessage,
                                                       CommandResultMessage<?> commandResultMessage) {
                                     fail("Expected exception to be thrown");
                                 }

                                 @Override
                                 public void onFailure(CommandMessage<?> commandMessage, Throwable cause) {
                                     assertEquals("Faking failed command handling", cause.getMessage());
                                 }
                             });

        InOrder inOrder = inOrder(mockInterceptor1, mockInterceptor2, commandHandler);
        inOrder.verify(mockInterceptor1).handle(
                isA(UnitOfWork.class), isA(InterceptorChain.class));
        inOrder.verify(mockInterceptor2).handle(
                isA(UnitOfWork.class), isA(InterceptorChain.class));
        inOrder.verify(commandHandler).handle(isA(GenericCommandMessage.class));
    }

    @SuppressWarnings({"ThrowableInstanceNeverThrown", "unchecked"})
    @Test
    public void testInterceptorChain_InterceptorThrowsException() throws Exception {
        MessageHandlerInterceptor<CommandMessage<?>> mockInterceptor1 =
                mock(MessageHandlerInterceptor.class, "stubName");
        final MessageHandlerInterceptor<CommandMessage<?>> mockInterceptor2 = mock(MessageHandlerInterceptor.class);
        when(mockInterceptor1.handle(isA(UnitOfWork.class), isA(InterceptorChain.class)))
                .thenAnswer(invocation -> ((InterceptorChain) invocation.getArguments()[1]).proceed());
        testSubject.registerHandlerInterceptor(mockInterceptor1);
        testSubject.registerHandlerInterceptor(mockInterceptor2);
        MessageHandler<CommandMessage<?>> commandHandler = mock(MessageHandler.class);
        when(commandHandler.handle(isA(CommandMessage.class))).thenReturn("Hi there!");
        testSubject.subscribe(String.class.getName(), commandHandler);
        RuntimeException someException = new RuntimeException("Mocking");
        doThrow(someException).when(mockInterceptor2).handle(isA(UnitOfWork.class), isA(InterceptorChain.class));
        testSubject.dispatch(asCommandMessage("Hi there!"),
                             new CommandCallback<Object, Object>() {
                                 @Override
                                 public void onSuccess(CommandMessage<?> commandMessage,
                                                       CommandResultMessage<?> commandResultMessage) {
                                     fail("Expected exception to be propagated");
                                 }

                                 @Override
                                 public void onFailure(CommandMessage<?> commandMessage, Throwable cause) {
                                     assertEquals("Mocking", cause.getMessage());
                                 }
                             });
        InOrder inOrder = inOrder(mockInterceptor1, mockInterceptor2, commandHandler);
        inOrder.verify(mockInterceptor1).handle(
                isA(UnitOfWork.class), isA(InterceptorChain.class));
        inOrder.verify(mockInterceptor2).handle(
                isA(UnitOfWork.class), isA(InterceptorChain.class));
        inOrder.verify(commandHandler, never()).handle(isA(CommandMessage.class));
    }

    @Test
    public void testCommandReplyMessageCorrelationData() {
        testSubject.subscribe(String.class.getName(), message -> message.getPayload().toString());
        testSubject.registerHandlerInterceptor(new CorrelationDataInterceptor<>(new MessageOriginProvider()));
        CommandMessage<String> command = asCommandMessage("Hi");
        testSubject.dispatch(command, new CommandCallback<String, String>() {
            @Override
            public void onSuccess(CommandMessage<? extends String> commandMessage,
                                  CommandResultMessage<? extends String> commandResultMessage) {
                assertEquals(command.getIdentifier(), commandResultMessage.getMetaData().get("traceId"));
                assertEquals(command.getIdentifier(), commandResultMessage.getMetaData().get("correlationId"));
                assertEquals(command.getPayload(), commandResultMessage.getPayload());
            }

            @Override
            public void onFailure(CommandMessage<? extends String> commandMessage, Throwable cause) {
                fail("Command execution should be successful");
            }
        });
    }

    private static class MyStringCommandHandler implements MessageHandler<CommandMessage<?>> {

        @Override
        public Object handle(CommandMessage<?> message) {
            return message;
        }
    }
}