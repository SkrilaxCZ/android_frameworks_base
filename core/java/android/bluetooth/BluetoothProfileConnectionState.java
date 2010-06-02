/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.bluetooth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Message;
import android.server.BluetoothA2dpService;
import android.server.BluetoothService;
import android.util.Log;

import com.android.internal.util.HierarchicalState;
import com.android.internal.util.HierarchicalStateMachine;

/**
 * This class is the Profile connection state machine associated with a remote
 * device. When the device bonds an instance of this class is created.
 * This tracks incoming and outgoing connections of all the profiles. Incoming
 * connections are preferred over outgoing connections and HFP preferred over
 * A2DP. When the device is unbonded, the instance is removed.
 *
 * States:
 * {@link BondedDevice}: This state represents a bonded device. When in this
 * state none of the profiles are in transition states.
 *
 * {@link OutgoingHandsfree}: Handsfree profile connection is in a transition
 * state because of a outgoing Connect or Disconnect.
 *
 * {@link IncomingHandsfree}: Handsfree profile connection is in a transition
 * state because of a incoming Connect or Disconnect.
 *
 * {@link IncomingA2dp}: A2dp profile connection is in a transition
 * state because of a incoming Connect or Disconnect.
 *
 * {@link OutgoingA2dp}: A2dp profile connection is in a transition
 * state because of a outgoing Connect or Disconnect.
 *
 * Todo(): Write tests for this class, when the Android Mock support is completed.
 * @hide
 */
public final class BluetoothProfileConnectionState extends HierarchicalStateMachine {
    private static final String TAG = "BluetoothProfileConnectionState";
    private static final boolean DBG = true; //STOPSHIP - Change to false

    public static final int CONNECT_HFP_OUTGOING = 1;
    public static final int CONNECT_HFP_INCOMING = 2;
    public static final int CONNECT_A2DP_OUTGOING = 3;
    public static final int CONNECT_A2DP_INCOMING = 4;

    public static final int DISCONNECT_HFP_OUTGOING = 5;
    private static final int DISCONNECT_HFP_INCOMING = 6;
    public static final int DISCONNECT_A2DP_OUTGOING = 7;
    public static final int DISCONNECT_A2DP_INCOMING = 8;

    public static final int UNPAIR = 9;
    public static final int AUTO_CONNECT_PROFILES = 10;
    public static final int TRANSITION_TO_STABLE = 11;

    private static final int AUTO_CONNECT_DELAY = 8000; // 8 secs

    private BondedDevice mBondedDevice = new BondedDevice();
    private OutgoingHandsfree mOutgoingHandsfree = new OutgoingHandsfree();
    private IncomingHandsfree mIncomingHandsfree = new IncomingHandsfree();
    private IncomingA2dp mIncomingA2dp = new IncomingA2dp();
    private OutgoingA2dp mOutgoingA2dp = new OutgoingA2dp();

    private Context mContext;
    private BluetoothService mService;
    private BluetoothA2dpService mA2dpService;
    private BluetoothHeadset  mHeadsetService;
    private boolean mHeadsetServiceConnected;

    private BluetoothDevice mDevice;
    private int mHeadsetState;
    private int mA2dpState;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (!device.equals(mDevice)) return;

            if (action.equals(BluetoothHeadset.ACTION_STATE_CHANGED)) {
                int newState = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, 0);
                int oldState = intent.getIntExtra(BluetoothHeadset.EXTRA_PREVIOUS_STATE, 0);
                int initiator = intent.getIntExtra(
                    BluetoothHeadset.EXTRA_DISCONNECT_INITIATOR,
                    BluetoothHeadset.LOCAL_DISCONNECT);
                mHeadsetState = newState;
                if (newState == BluetoothHeadset.STATE_DISCONNECTED &&
                    initiator == BluetoothHeadset.REMOTE_DISCONNECT) {
                    sendMessage(DISCONNECT_HFP_INCOMING);
                }
                if (newState == BluetoothHeadset.STATE_CONNECTED ||
                    newState == BluetoothHeadset.STATE_DISCONNECTED) {
                    sendMessage(TRANSITION_TO_STABLE);
                }
            } else if (action.equals(BluetoothA2dp.ACTION_SINK_STATE_CHANGED)) {
                int newState = intent.getIntExtra(BluetoothA2dp.EXTRA_SINK_STATE, 0);
                int oldState = intent.getIntExtra(BluetoothA2dp.EXTRA_PREVIOUS_SINK_STATE, 0);
                mA2dpState = newState;
                if ((oldState == BluetoothA2dp.STATE_CONNECTED ||
                           oldState == BluetoothA2dp.STATE_PLAYING) &&
                           newState == BluetoothA2dp.STATE_DISCONNECTED) {
                    sendMessage(DISCONNECT_A2DP_INCOMING);
                }
                if (newState == BluetoothA2dp.STATE_CONNECTED ||
                    newState == BluetoothA2dp.STATE_DISCONNECTED) {
                    sendMessage(TRANSITION_TO_STABLE);
                }
            } else if (action.equals(BluetoothDevice.ACTION_ACL_CONNECTED)) {
                if (!getCurrentState().equals(mBondedDevice)) {
                    Log.e(TAG, "State is: " + getCurrentState());
                    return;
                }
                Message msg = new Message();
                msg.what = AUTO_CONNECT_PROFILES;
                sendMessageDelayed(msg, AUTO_CONNECT_DELAY);
            }
      }
    };

    public BluetoothProfileConnectionState(Context context, String address,
          BluetoothService service, BluetoothA2dpService a2dpService) {
        super(address);
        mContext = context;
        mDevice = new BluetoothDevice(address);
        mService = service;
        mA2dpService = a2dpService;

        addState(mBondedDevice);
        addState(mOutgoingHandsfree);
        addState(mIncomingHandsfree);
        addState(mIncomingA2dp);
        addState(mOutgoingA2dp);
        setInitialState(mBondedDevice);

        IntentFilter filter = new IntentFilter();
        // Fine-grained state broadcasts
        filter.addAction(BluetoothA2dp.ACTION_SINK_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);

        mContext.registerReceiver(mBroadcastReceiver, filter);

        HeadsetServiceListener l = new HeadsetServiceListener();
    }

    private class HeadsetServiceListener implements BluetoothHeadset.ServiceListener {
        public HeadsetServiceListener() {
            mHeadsetService = new BluetoothHeadset(mContext, this);
        }
        public void onServiceConnected() {
            synchronized(BluetoothProfileConnectionState.this) {
                mHeadsetServiceConnected = true;
            }
        }
        public void onServiceDisconnected() {
            synchronized(BluetoothProfileConnectionState.this) {
                mHeadsetServiceConnected = false;
            }
        }
    }

    private class BondedDevice extends HierarchicalState {
        @Override
        protected void enter() {
            log("Entering ACL Connected state with: " + getCurrentMessage().what);
            Message m = new Message();
            m.copyFrom(getCurrentMessage());
            sendMessageAtFrontOfQueue(m);
        }
        @Override
        protected boolean processMessage(Message message) {
            log("ACL Connected State -> Processing Message: " + message.what);
            switch(message.what) {
                case CONNECT_HFP_OUTGOING:
                case DISCONNECT_HFP_OUTGOING:
                    transitionTo(mOutgoingHandsfree);
                    break;
                case CONNECT_HFP_INCOMING:
                    transitionTo(mIncomingHandsfree);
                    break;
                case DISCONNECT_HFP_INCOMING:
                    transitionTo(mIncomingHandsfree);
                    break;
                case CONNECT_A2DP_OUTGOING:
                case DISCONNECT_A2DP_OUTGOING:
                    transitionTo(mOutgoingA2dp);
                    break;
                case CONNECT_A2DP_INCOMING:
                case DISCONNECT_A2DP_INCOMING:
                    transitionTo(mIncomingA2dp);
                    break;
                case UNPAIR:
                    if (mHeadsetState != BluetoothHeadset.STATE_DISCONNECTED) {
                        sendMessage(DISCONNECT_HFP_OUTGOING);
                        deferMessage(message);
                        break;
                    } else if (mA2dpState != BluetoothA2dp.STATE_DISCONNECTED) {
                        sendMessage(DISCONNECT_A2DP_OUTGOING);
                        deferMessage(message);
                        break;
                    }
                    processCommand(UNPAIR);
                    break;
                case AUTO_CONNECT_PROFILES:
                    if (!mHeadsetServiceConnected) {
                        deferMessage(message);
                    } else {
                        if (mHeadsetService.getPriority(mDevice) ==
                            BluetoothHeadset.PRIORITY_AUTO_CONNECT) {
                            mHeadsetService.connectHeadset(mDevice);
                        }
                        if (mA2dpService != null &&
                            mA2dpService.getSinkPriority(mDevice) ==
                              BluetoothA2dp.PRIORITY_AUTO_CONNECT) {
                            mA2dpService.connectSink(mDevice);
                        }
                    }
                    break;
                case TRANSITION_TO_STABLE:
                    // ignore.
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private class OutgoingHandsfree extends HierarchicalState {
        private boolean mStatus = false;
        private int mCommand;

        @Override
        protected void enter() {
            log("Entering OutgoingHandsfree state with: " + getCurrentMessage().what);
            mCommand = getCurrentMessage().what;
            if (mCommand != CONNECT_HFP_OUTGOING &&
                mCommand != DISCONNECT_HFP_OUTGOING) {
                Log.e(TAG, "Error: OutgoingHandsfree state with command:" + mCommand);
            }
            mStatus = processCommand(mCommand);
            if (!mStatus) sendMessage(TRANSITION_TO_STABLE);
        }

        @Override
        protected boolean processMessage(Message message) {
            log("OutgoingHandsfree State -> Processing Message: " + message.what);
            Message deferMsg = new Message();
            int command = message.what;
            switch(command) {
                case CONNECT_HFP_OUTGOING:
                    if (command != mCommand) {
                        // Disconnect followed by a connect - defer
                        deferMessage(message);
                    }
                    break;
                case CONNECT_HFP_INCOMING:
                    if (mCommand == CONNECT_HFP_OUTGOING) {
                        // Cancel outgoing connect, accept incoming
                        cancelCommand(CONNECT_HFP_OUTGOING);
                        transitionTo(mIncomingHandsfree);
                    } else {
                        // We have done the disconnect but we are not
                        // sure which state we are in at this point.
                        deferMessage(message);
                    }
                    break;
                case CONNECT_A2DP_INCOMING:
                    // accept incoming A2DP, retry HFP_OUTGOING
                    transitionTo(mIncomingA2dp);

                    if (mStatus) {
                        deferMsg.what = mCommand;
                        deferMessage(deferMsg);
                    }
                    break;
                case CONNECT_A2DP_OUTGOING:
                    deferMessage(message);
                    break;
                case DISCONNECT_HFP_OUTGOING:
                    if (mCommand == CONNECT_HFP_OUTGOING) {
                        // Cancel outgoing connect
                        cancelCommand(CONNECT_HFP_OUTGOING);
                        processCommand(DISCONNECT_HFP_OUTGOING);
                    }
                    // else ignore
                    break;
                case DISCONNECT_HFP_INCOMING:
                    // When this happens the socket would be closed and the headset
                    // state moved to DISCONNECTED, cancel the outgoing thread.
                    // if it still is in CONNECTING state
                    cancelCommand(CONNECT_HFP_OUTGOING);
                    break;
                case DISCONNECT_A2DP_OUTGOING:
                    deferMessage(message);
                    break;
                case DISCONNECT_A2DP_INCOMING:
                    // Bluez will handle the disconnect. If because of this the outgoing
                    // handsfree connection has failed, then retry.
                    if (mStatus) {
                       deferMsg.what = mCommand;
                       deferMessage(deferMsg);
                    }
                    break;
                case UNPAIR:
                case AUTO_CONNECT_PROFILES:
                    deferMessage(message);
                    break;
                case TRANSITION_TO_STABLE:
                    transitionTo(mBondedDevice);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private class IncomingHandsfree extends HierarchicalState {
        private boolean mStatus = false;
        private int mCommand;

        @Override
        protected void enter() {
            log("Entering IncomingHandsfree state with: " + getCurrentMessage().what);
            mCommand = getCurrentMessage().what;
            if (mCommand != CONNECT_HFP_INCOMING &&
                mCommand != DISCONNECT_HFP_INCOMING) {
                Log.e(TAG, "Error: IncomingHandsfree state with command:" + mCommand);
            }
            mStatus = processCommand(mCommand);
            if (!mStatus) sendMessage(TRANSITION_TO_STABLE);
        }

        @Override
        protected boolean processMessage(Message message) {
            log("IncomingHandsfree State -> Processing Message: " + message.what);
            switch(message.what) {
                case CONNECT_HFP_OUTGOING:
                    deferMessage(message);
                    break;
                case CONNECT_HFP_INCOMING:
                    // Ignore
                    Log.e(TAG, "Error: Incoming connection with a pending incoming connection");
                    break;
                case CONNECT_A2DP_INCOMING:
                    // Serialize the commands.
                    deferMessage(message);
                    break;
                case CONNECT_A2DP_OUTGOING:
                    deferMessage(message);
                    break;
                case DISCONNECT_HFP_OUTGOING:
                    // We don't know at what state we are in the incoming HFP connection state.
                    // We can be changing from DISCONNECTED to CONNECTING, or
                    // from CONNECTING to CONNECTED, so serializing this command is
                    // the safest option.
                    deferMessage(message);
                    break;
                case DISCONNECT_HFP_INCOMING:
                    // Nothing to do here, we will already be DISCONNECTED
                    // by this point.
                    break;
                case DISCONNECT_A2DP_OUTGOING:
                    deferMessage(message);
                    break;
                case DISCONNECT_A2DP_INCOMING:
                    // Bluez handles incoming A2DP disconnect.
                    // If this causes incoming HFP to fail, it is more of a headset problem
                    // since both connections are incoming ones.
                    break;
                case UNPAIR:
                case AUTO_CONNECT_PROFILES:
                    deferMessage(message);
                    break;
                case TRANSITION_TO_STABLE:
                    transitionTo(mBondedDevice);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private class OutgoingA2dp extends HierarchicalState {
        private boolean mStatus = false;
        private int mCommand;

        @Override
        protected void enter() {
            log("Entering OutgoingA2dp state with: " + getCurrentMessage().what);
            mCommand = getCurrentMessage().what;
            if (mCommand != CONNECT_A2DP_OUTGOING &&
                mCommand != DISCONNECT_A2DP_OUTGOING) {
                Log.e(TAG, "Error: OutgoingA2DP state with command:" + mCommand);
            }
            mStatus = processCommand(mCommand);
            if (!mStatus) sendMessage(TRANSITION_TO_STABLE);
        }

        @Override
        protected boolean processMessage(Message message) {
            log("OutgoingA2dp State->Processing Message: " + message.what);
            Message deferMsg = new Message();
            switch(message.what) {
                case CONNECT_HFP_OUTGOING:
                    processCommand(CONNECT_HFP_OUTGOING);

                    // Don't cancel A2DP outgoing as there is no guarantee it
                    // will get canceled.
                    // It might already be connected but we might not have got the
                    // A2DP_SINK_STATE_CHANGE. Hence, no point disconnecting here.
                    // The worst case, the connection will fail, retry.
                    // The same applies to Disconnecting an A2DP connection.
                    if (mStatus) {
                        deferMsg.what = mCommand;
                        deferMessage(deferMsg);
                    }
                    break;
                case CONNECT_HFP_INCOMING:
                    processCommand(CONNECT_HFP_INCOMING);

                    // Don't cancel A2DP outgoing as there is no guarantee
                    // it will get canceled.
                    // The worst case, the connection will fail, retry.
                    if (mStatus) {
                        deferMsg.what = mCommand;
                        deferMessage(deferMsg);
                    }
                    break;
                case CONNECT_A2DP_INCOMING:
                    // Bluez will take care of conflicts between incoming and outgoing
                    // connections.
                    transitionTo(mIncomingA2dp);
                    break;
                case CONNECT_A2DP_OUTGOING:
                    // Ignore
                    break;
                case DISCONNECT_HFP_OUTGOING:
                    deferMessage(message);
                    break;
                case DISCONNECT_HFP_INCOMING:
                    // At this point, we are already disconnected
                    // with HFP. Sometimes A2DP connection can
                    // fail due to the disconnection of HFP. So add a retry
                    // for the A2DP.
                    if (mStatus) {
                        deferMsg.what = mCommand;
                        deferMessage(deferMsg);
                    }
                    break;
                case DISCONNECT_A2DP_OUTGOING:
                    processCommand(DISCONNECT_A2DP_OUTGOING);
                    break;
                case DISCONNECT_A2DP_INCOMING:
                    // Ignore, will be handled by Bluez
                    break;
                case UNPAIR:
                case AUTO_CONNECT_PROFILES:
                    deferMessage(message);
                    break;
                case TRANSITION_TO_STABLE:
                    transitionTo(mBondedDevice);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private class IncomingA2dp extends HierarchicalState {
        private boolean mStatus = false;
        private int mCommand;

        @Override
        protected void enter() {
            log("Entering IncomingA2dp state with: " + getCurrentMessage().what);
            mCommand = getCurrentMessage().what;
            if (mCommand != CONNECT_A2DP_INCOMING &&
                mCommand != DISCONNECT_A2DP_INCOMING) {
                Log.e(TAG, "Error: IncomingA2DP state with command:" + mCommand);
            }
            mStatus = processCommand(mCommand);
            if (!mStatus) sendMessage(TRANSITION_TO_STABLE);
        }

        @Override
        protected boolean processMessage(Message message) {
            log("IncomingA2dp State->Processing Message: " + message.what);
            Message deferMsg = new Message();
            switch(message.what) {
                case CONNECT_HFP_OUTGOING:
                    deferMessage(message);
                    break;
                case CONNECT_HFP_INCOMING:
                    // Shouldn't happen, but serialize the commands.
                    deferMessage(message);
                    break;
                case CONNECT_A2DP_INCOMING:
                    // ignore
                    break;
                case CONNECT_A2DP_OUTGOING:
                    // Defer message and retry
                    deferMessage(message);
                    break;
                case DISCONNECT_HFP_OUTGOING:
                    deferMessage(message);
                    break;
                case DISCONNECT_HFP_INCOMING:
                    // Shouldn't happen but if does, we can handle it.
                    // Depends if the headset can handle it.
                    // Incoming A2DP will be handled by Bluez, Disconnect HFP
                    // the socket would have already been closed.
                    // ignore
                    break;
                case DISCONNECT_A2DP_OUTGOING:
                    deferMessage(message);
                    break;
                case DISCONNECT_A2DP_INCOMING:
                    // Ignore, will be handled by Bluez
                    break;
                case UNPAIR:
                case AUTO_CONNECT_PROFILES:
                    deferMessage(message);
                    break;
                case TRANSITION_TO_STABLE:
                    transitionTo(mBondedDevice);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }



    synchronized void cancelCommand(int command) {
        if (command == CONNECT_HFP_OUTGOING ) {
            // Cancel the outgoing thread.
            if (mHeadsetServiceConnected) {
                mHeadsetService.cancelConnectThread();
            }
            // HeadsetService is down. Phone process most likely crashed.
            // The thread would have got killed.
        }
    }

    synchronized void deferHeadsetMessage(int command) {
        Message msg = new Message();
        msg.what = command;
        deferMessage(msg);
    }

    synchronized boolean processCommand(int command) {
        log("Processing command:" + command);
        switch(command) {
            case  CONNECT_HFP_OUTGOING:
                if (mHeadsetService != null) {
                    return mHeadsetService.connectHeadsetInternal(mDevice);
                }
                break;
            case CONNECT_HFP_INCOMING:
                if (!mHeadsetServiceConnected) {
                    deferHeadsetMessage(command);
                } else if (mHeadsetState == BluetoothHeadset.STATE_CONNECTING) {
                    return mHeadsetService.acceptIncomingConnect(mDevice);
                } else if (mHeadsetState == BluetoothHeadset.STATE_DISCONNECTED) {
                    return mHeadsetService.createIncomingConnect(mDevice);
                }
                break;
            case CONNECT_A2DP_OUTGOING:
                if (mA2dpService != null) {
                    return mA2dpService.connectSinkInternal(mDevice);
                }
                break;
            case CONNECT_A2DP_INCOMING:
                // ignore, Bluez takes care
                return true;
            case DISCONNECT_HFP_OUTGOING:
                if (!mHeadsetServiceConnected) {
                    deferHeadsetMessage(command);
                } else {
                    if (mHeadsetService.getPriority(mDevice) ==
                        BluetoothHeadset.PRIORITY_AUTO_CONNECT) {
                        mHeadsetService.setPriority(mDevice, BluetoothHeadset.PRIORITY_ON);
                    }
                    return mHeadsetService.disconnectHeadsetInternal(mDevice);
                }
                break;
            case DISCONNECT_HFP_INCOMING:
                // ignore
                return true;
            case DISCONNECT_A2DP_INCOMING:
                // ignore
                return true;
            case DISCONNECT_A2DP_OUTGOING:
                if (mA2dpService != null) {
                    if (mA2dpService.getSinkPriority(mDevice) ==
                        BluetoothA2dp.PRIORITY_AUTO_CONNECT) {
                        mA2dpService.setSinkPriority(mDevice, BluetoothHeadset.PRIORITY_ON);
                    }
                    return mA2dpService.disconnectSinkInternal(mDevice);
                }
                break;
            case UNPAIR:
                return mService.removeBondInternal(mDevice.getAddress());
            default:
                Log.e(TAG, "Error: Unknown Command");
        }
        return false;
    }

    private void log(String message) {
        if (DBG) {
            Log.i(TAG, "Device:" + mDevice + " Message:" + message);
        }
    }
}
