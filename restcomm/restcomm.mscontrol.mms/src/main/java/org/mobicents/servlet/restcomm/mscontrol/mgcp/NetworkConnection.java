/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2013, Telestax Inc and individual contributors
 * by the @authors tag. 
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.servlet.restcomm.mscontrol.mgcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.mobicents.servlet.restcomm.fsm.Action;
import org.mobicents.servlet.restcomm.fsm.FiniteStateMachine;
import org.mobicents.servlet.restcomm.fsm.State;
import org.mobicents.servlet.restcomm.fsm.Transition;
import org.mobicents.servlet.restcomm.mgcp.ConnectionStateChanged;
import org.mobicents.servlet.restcomm.mgcp.EndpointStateChanged;
import org.mobicents.servlet.restcomm.mgcp.LinkStateChanged;
import org.mobicents.servlet.restcomm.mgcp.MediaGatewayResponse;
import org.mobicents.servlet.restcomm.mgcp.MediaSession;
import org.mobicents.servlet.restcomm.mscontrol.messages.CloseConnection;
import org.mobicents.servlet.restcomm.mscontrol.messages.Join;
import org.mobicents.servlet.restcomm.mscontrol.messages.Leave;
import org.mobicents.servlet.restcomm.mscontrol.messages.UpdateMediaSession;
import org.mobicents.servlet.restcomm.mscontrol.mgcp.messages.CreateNetworkConnection;
import org.mobicents.servlet.restcomm.patterns.Observe;
import org.mobicents.servlet.restcomm.patterns.StopObserving;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

/**
 * Represents an MGCP-based implementation of a JSR309 NetworkConnection actor.
 * <p>
 * The NetworkConnection actor is responsible for managing a Bridge endpoint and a Media Group, as well as any links between
 * them.
 * <p>
 * It is also responsible for establishing the RTP connection with remote peer via SDP negotiation.
 * </p>
 * 
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 *
 */
public class NetworkConnection extends UntypedActor {

    private final LoggingAdapter logger = Logging.getLogger(getContext().system(), this);

    // Media Components
    private final ActorRef gateway;
    private final MediaSession session;
    private final ActorRef endpoint;

    // Finite State Machine
    private final FiniteStateMachine fsm;
    private final State uninitialized;
    private final State acquiringBridge;
    private final State acquiringConnection;
    private final State initializingConnection;
    private final State openingConnection;
    private final State pending;
    private final State updatingConnection;
    private final State closingLink;
    private final State active;
    private final State acquiringLink;
    private final State initializingLink;
    private final State openingLink;
    private final State closing;
    private final State failed;
    private final State closed;

    // Call State
    private final List<ActorRef> observers;
    private volatile boolean joined;
    private volatile boolean close;

    public NetworkConnection(final ActorRef gateway, final MediaSession session, final ActorRef endpoint) {
        super();
        final ActorRef source = self();

        // Media Components
        this.gateway = gateway;
        this.session = session;
        this.endpoint = endpoint;

        // Finite State Machine
        this.uninitialized = new State("uninitialized", null);
        this.acquiringBridge = new State("acquiring bridge", new AcquiringBridge(source));
        this.acquiringConnection = new State("acquiring connection", new AcquiringConnection(source));
        this.initializingConnection = new State("initializing connection", new InitializingConnection(source));
        this.openingConnection = new State("opening connection", new OpeningConnection(source));
        this.pending = new State("pending", new Pending(source));
        this.updatingConnection = new State("updating connection", new UpdatingConnection(source));
        this.closingLink = new State("closing link", new ClosingLink(source));
        this.active = new State("active", new Active(source));
        this.acquiringLink = new State("acquiring link", new AcquiringLink(source));
        this.initializingLink = new State("initializing link", new InitializingLink(source));
        this.openingLink = new State("opening link", new OpeningLink(source));
        this.closing = new State("closing", new Closing(source));
        this.failed = new State("failed", new Failed(source));
        this.closed = new State("closed", new Closed(source));

        final Set<Transition> transitions = new HashSet<Transition>();
        transitions.add(new Transition(uninitialized, acquiringBridge));
        transitions.add(new Transition(acquiringBridge, acquiringConnection));
        transitions.add(new Transition(acquiringBridge, closed));
        transitions.add(new Transition(acquiringConnection, initializingConnection));
        transitions.add(new Transition(acquiringConnection, closed));
        transitions.add(new Transition(initializingConnection, openingConnection));
        transitions.add(new Transition(initializingConnection, closed));
        transitions.add(new Transition(openingConnection, pending));
        transitions.add(new Transition(openingConnection, active));
        transitions.add(new Transition(openingConnection, failed));
        // XXX transitions.add(new Transition(openingConnection, closing)); WHAT TO DO WHEN CLOSE IS RECEIVED ????
        transitions.add(new Transition(pending, closing));
        transitions.add(new Transition(pending, updatingConnection));
        transitions.add(new Transition(updatingConnection, closing));
        transitions.add(new Transition(updatingConnection, active));
        // XXX transitions.add(new Transition(updatingConnection, closingLink)); SHOULD WE CLOSE LINK ????
        transitions.add(new Transition(active, updatingConnection));
        transitions.add(new Transition(active, closingLink));
        transitions.add(new Transition(active, acquiringLink));
        transitions.add(new Transition(active, closing));
        transitions.add(new Transition(closingLink, active));
        transitions.add(new Transition(closingLink, closing));
        transitions.add(new Transition(acquiringLink, initializingLink));
        transitions.add(new Transition(acquiringLink, closing));
        transitions.add(new Transition(initializingLink, openingLink));
        transitions.add(new Transition(initializingLink, closing));
        transitions.add(new Transition(openingLink, active));
        transitions.add(new Transition(openingLink, closing));
        transitions.add(new Transition(closing, failed));
        transitions.add(new Transition(closing, closed));
        this.fsm = new FiniteStateMachine(uninitialized, transitions);

        // Call State
        this.observers = new ArrayList<ActorRef>(3);
        this.joined = false;
        this.close = false;
    }

    @Override
    public void onReceive(Object message) throws Exception {
        final Class<?> klass = message.getClass();
        final ActorRef self = self();
        final ActorRef sender = sender();
        final State state = fsm.state();

        logger.info("********** Network Connection Current State: \"" + state.toString());
        logger.info("********** Network Connection Processing Message: \"" + klass.getName() + " sender : " + sender.path());

        if (Observe.class.equals(klass)) {
            onObserve((Observe) message, self, sender);
        } else if (StopObserving.class.equals(klass)) {
            onStopObserving((StopObserving) message, self, sender);
        } else if (CreateNetworkConnection.class.equals(klass)) {
            onCreateNetworkConnection((CreateNetworkConnection) message, self, sender);
        } else if (MediaGatewayResponse.class.equals(klass)) {
            onMediaGatewayResponse((MediaGatewayResponse<?>) message, self, sender);
        } else if (ConnectionStateChanged.class.equals(klass)) {
            onConnectionStateChanged((ConnectionStateChanged) message, self, sender);
        } else if (UpdateMediaSession.class.equals(klass)) {
            onUpdateMediaSession((UpdateMediaSession) message, self, sender);
        } else if (Join.class.equals(klass)) {
            onJoin((Join) message, self, sender);
        } else if (Leave.class.equals(klass)) {
            onLeave((Leave) message, self, sender);
        } else if (LinkStateChanged.class.equals(klass)) {
            onLinkStateChanged((LinkStateChanged) message, self, sender);
        } else if (CloseConnection.class.equals(klass)) {
            onCloseConnection((CloseConnection) message, self, sender);
        } else if (EndpointStateChanged.class.equals(klass)) {
            onEndpointStateChanged((EndpointStateChanged) message, self, sender);
        }

    }

    /*
     * EVENTS
     */

    private void onObserve(Observe message, ActorRef self, ActorRef sender) {
        // TODO Auto-generated method stub

    }

    private void onStopObserving(StopObserving message, ActorRef self, ActorRef sender) {
        // TODO Auto-generated method stub

    }

    private void onCreateNetworkConnection(CreateNetworkConnection message, ActorRef self, ActorRef sender) {
        // TODO Auto-generated method stub

    }

    private void onMediaGatewayResponse(MediaGatewayResponse<?> message, ActorRef self, ActorRef sender) {
        // TODO Auto-generated method stub

    }

    private void onConnectionStateChanged(ConnectionStateChanged message, ActorRef self, ActorRef sender) {
        // TODO Auto-generated method stub

    }

    private void onUpdateMediaSession(UpdateMediaSession message, ActorRef self, ActorRef sender) {
        // TODO Auto-generated method stub

    }

    private void onJoin(Join message, ActorRef self, ActorRef sender) {
        // TODO Auto-generated method stub

    }

    private void onLeave(Leave message, ActorRef self, ActorRef sender) {
        // TODO Auto-generated method stub

    }

    private void onLinkStateChanged(LinkStateChanged message, ActorRef self, ActorRef sender) {
        // TODO Auto-generated method stub

    }

    private void onCloseConnection(CloseConnection message, ActorRef self, ActorRef sender) {
        // TODO Auto-generated method stub

    }

    private void onEndpointStateChanged(EndpointStateChanged message, ActorRef self, ActorRef sender) {
        // TODO Auto-generated method stub

    }

    /*
     * ACTIONS
     */
    private abstract class AbstractAction implements Action {
        protected final ActorRef source;

        public AbstractAction(final ActorRef source) {
            super();
            this.source = source;
        }
    }

    private final class AcquiringBridge extends AbstractAction {

        public AcquiringBridge(ActorRef source) {
            super(source);
            // TODO Auto-generated constructor stub
        }

        @Override
        public void execute(Object message) throws Exception {
            // TODO Auto-generated method stub

        }

    }

    private final class AcquiringConnection extends AbstractAction {

        public AcquiringConnection(ActorRef source) {
            super(source);
            // TODO Auto-generated constructor stub
        }

        @Override
        public void execute(Object message) throws Exception {
            // TODO Auto-generated method stub

        }

    }

    private final class InitializingConnection extends AbstractAction {

        public InitializingConnection(ActorRef source) {
            super(source);
            // TODO Auto-generated constructor stub
        }

        @Override
        public void execute(Object message) throws Exception {
            // TODO Auto-generated method stub

        }

    }

    private final class OpeningConnection extends AbstractAction {

        public OpeningConnection(ActorRef source) {
            super(source);
            // TODO Auto-generated constructor stub
        }

        @Override
        public void execute(Object message) throws Exception {
            // TODO Auto-generated method stub

        }

    }

    private final class Pending extends AbstractAction {

        public Pending(ActorRef source) {
            super(source);
            // TODO Auto-generated constructor stub
        }

        @Override
        public void execute(Object message) throws Exception {
            // TODO Auto-generated method stub

        }

    }

    private final class UpdatingConnection extends AbstractAction {

        public UpdatingConnection(ActorRef source) {
            super(source);
            // TODO Auto-generated constructor stub
        }

        @Override
        public void execute(Object message) throws Exception {
            // TODO Auto-generated method stub

        }

    }

    private final class ClosingLink extends AbstractAction {

        public ClosingLink(ActorRef source) {
            super(source);
            // TODO Auto-generated constructor stub
        }

        @Override
        public void execute(Object message) throws Exception {
            // TODO Auto-generated method stub

        }

    }

    private final class Active extends AbstractAction {

        public Active(ActorRef source) {
            super(source);
            // TODO Auto-generated constructor stub
        }

        @Override
        public void execute(Object message) throws Exception {
            // TODO Auto-generated method stub

        }

    }

    private final class AcquiringLink extends AbstractAction {

        public AcquiringLink(ActorRef source) {
            super(source);
            // TODO Auto-generated constructor stub
        }

        @Override
        public void execute(Object message) throws Exception {
            // TODO Auto-generated method stub

        }

    }

    private final class InitializingLink extends AbstractAction {

        public InitializingLink(ActorRef source) {
            super(source);
            // TODO Auto-generated constructor stub
        }

        @Override
        public void execute(Object message) throws Exception {
            // TODO Auto-generated method stub

        }

    }

    private final class OpeningLink extends AbstractAction {

        public OpeningLink(ActorRef source) {
            super(source);
            // TODO Auto-generated constructor stub
        }

        @Override
        public void execute(Object message) throws Exception {
            // TODO Auto-generated method stub

        }

    }

    private final class Closing extends AbstractAction {

        public Closing(ActorRef source) {
            super(source);
            // TODO Auto-generated constructor stub
        }

        @Override
        public void execute(Object message) throws Exception {
            // TODO Auto-generated method stub

        }

    }

    private final class Failed extends AbstractAction {

        public Failed(ActorRef source) {
            super(source);
            // TODO Auto-generated constructor stub
        }

        @Override
        public void execute(Object message) throws Exception {
            // TODO Auto-generated method stub

        }

    }

    private final class Closed extends AbstractAction {

        public Closed(ActorRef source) {
            super(source);
            // TODO Auto-generated constructor stub
        }

        @Override
        public void execute(Object message) throws Exception {
            // TODO Auto-generated method stub

        }

    }
}