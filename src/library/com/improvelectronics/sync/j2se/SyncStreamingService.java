/*****************************************************************************
 Copyright © 2014 Kent Displays, Inc.

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.
 ****************************************************************************/

package com.improvelectronics.sync.j2se;

import java.util.logging.Logger;

import com.improvelectronics.sync.Config;
import com.improvelectronics.sync.hid.HIDMessage;
import com.improvelectronics.sync.hid.HIDSetReport;
import com.improvelectronics.sync.hid.HIDUtilities;
import com.javaquery.bluetooth.ServicesSearch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import javax.bluetooth.UUID;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.io.StreamConnectionNotifier;

/**
 * This service connects to the Boogie Board Sync devices and communicates with the Sync using a custom implementation of the HID protocol. All of the
 * connections are done automatically since this service is always running while Bluetooth is enabled. A client of this service can add a listener,
 * that implements {@link com.improvelectronics.sync.android.SyncStreamingListener #SyncStreamingListener},
 * to listen for changes of the streaming service as well as send commands to the connected Boogie Board Sync.
 * </p>
 * This service also handles all the notifications that are displayed when the Sync connects and disconnects. It is necessary to display these
 * notifications since the Android OS does not show a current Bluetooth connection with the Bluetooth icon in the status bar. Class also handles
 * the case when the user has outdated firmware and will direct them to a site with instructions on how to update the firmware.
 */
public class SyncStreamingService {
    private static final Logger Log = Logger.getLogger(SyncStreamingService.class.getName());
    
    private static final UUID LISTEN_UUID = new UUID("d6a56f8188f811e3baa80800200c9a66", false);
    private static final UUID CONNECT_UUID = new UUID("d6a56f8088f811e3baa80800200c9a66", false);
    
    private static final boolean DEBUG = Config.DEBUG;;
    private List<SyncStreamingListener> mListeners;
    private int mState, mMode;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private AcceptThread mAcceptThread;
    private List<SyncPath> mPaths;
    private ArrayList<String> devicesUrlList;

    // Used for updating the local time of the Sync.
    private static final int YEAR_OFFSET = 1980;

    // Communication with background thread.
    private MessageHandler mMessageHandler;
    private static final int MESSAGE_DATA = 13;
    private static final int MESSAGE_CONNECTED = 14;
    private static final int MESSAGE_CONNECTION_BROKEN = 15;

    /**
     * The Sync streaming service is in connected state.
     */
    public static final int STATE_CONNECTED = 0;

    /**
     * The Sync streaming service is in connecting state.
     */
    public static final int STATE_CONNECTING = 1;

    /**
     * The Sync streaming service is in disconnected state.
     */
    public static final int STATE_DISCONNECTED = 2;

    /**
     * The Sync streaming service is in listening state.
     */
    public static final int STATE_LISTENING = 4;

    /**
     * This mode tells the Sync to not report any information and be silent to the client. This greatly saves battery life of the Sync and the Android
     * device.
     */
    public static final int MODE_NONE = 1;

    /**
     * This mode tells the Sync to report every button push and path to the client.
     */
    public static final int MODE_CAPTURE = 4;

    /**
     * This mode tells the Sync to only inform the client when it has saved a file.
     */
    public static final int MODE_FILE = 5;

    private static final String ACTION_BASE = "com.improvelectronics.sync.android.SyncStreamingService.action";

    /**
     * Broadcast Action: Button was pushed on the Sync.
     */
    public static final String ACTION_BUTTON_PUSHED = ACTION_BASE + ".BUTTON_PUSHED";

    /**
     * Broadcast Action: The state of the Sync streaming service changed.
     */
    public static final String ACTION_STATE_CHANGED = ACTION_BASE + ".STATE_CHANGED";

    /**
     * Used as an int extra field in {@link #ACTION_BUTTON_PUSHED} intents for button push from the Sync.
     */
    public static final String EXTRA_BUTTON_PUSHED = "EXTRA_BUTTON_PUSHED";

    /**
     * Used as an int extra field in {@link #ACTION_STATE_CHANGED} intents for current state.
     */
    public static final String EXTRA_STATE = "EXTRA_STATE";

    /**
     * Used as an int extra field in {@link #ACTION_STATE_CHANGED} intents for previous state.
     */
    public static final String EXTRA_PREVIOUS_STATE = "PREVIOUS_STATE";

    /**
     * Used as an BluetoothDevice extra field in {@link #ACTION_STATE_CHANGED} intents for when streaming service reports a
     * connected state.
     */
    public static final String EXTRA_DEVICE = "EXTRA_DEVICE";

    /**
     * Used as an int extra for when the save button is pushed.
     */
    public static final int SAVE_BUTTON = 13;

    public SyncStreamingService(String syncURL) {
        // Set the default properties.
        mPaths = new ArrayList<SyncPath>();
        mListeners = new ArrayList<SyncStreamingListener>();
        devicesUrlList = new ArrayList<>();
        mMessageHandler = new MessageHandler();
        mState = STATE_DISCONNECTED;
        mMode = MODE_NONE;
        
        if(syncURL == null) {
            findPairedDevices();
        } else {
            devicesUrlList.add(syncURL);
        }
        
        // start the connection
        start();
    }

    /**
     * Returns the current state of the Sync streaming service.
     *
     * @return state
     */
    public int getState() {
        return mState;
    }
    
    /**
     * Start the streaming service. Check to see if we have paired devices and connect if necessary.
     */
    private synchronized void start() {
        if (DEBUG) Log.log(Level.INFO, "start");      
        
        // Start the thread to listen on a BluetoothServerSocket
        if (mAcceptThread == null) {
            mAcceptThread = new AcceptThread();
            mAcceptThread.start();
        }

        // Only change state to listening if we are disconnected.
        if(mState == STATE_DISCONNECTED) {
            updateDeviceState(STATE_LISTENING);
        }

        if(mState != STATE_CONNECTED && mState != STATE_CONNECTING) {            
            if(!devicesUrlList.isEmpty()) {
                String connectionURL = devicesUrlList.get(0);
                connect(connectionURL);
            }
        }      
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     *
     * @param device The BluetoothDevice to connect
     */
    private synchronized void connect(String connectionURL) {
        if (DEBUG) Log.log(Level.INFO, "connect to: " + connectionURL);

        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(connectionURL);
        mConnectThread.start();
        updateDeviceState(STATE_CONNECTING);
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     *
     * @param connection The BluetoothSocket on which the connection was made
     */
    private synchronized void connected(StreamConnection streamConnection) {
        if (DEBUG) Log.log(Level.INFO, "connected");

        // Cancel the thread that completed the connection.
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection.
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Start listening thread if there is no one already running.
        if (mAcceptThread == null) {
            mAcceptThread = new AcceptThread();
            mAcceptThread.start();
        }

        // Start the thread to manage the connection and perform transmissions.
        mConnectedThread = new ConnectedThread(streamConnection);
        mConnectedThread.start();

        updateDeviceState(STATE_CONNECTED);
    }

    /**
     * Stop all threads.
     */
    private synchronized void stop() {
        if (DEBUG) Log.log(Level.INFO, "stop");

        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if (mAcceptThread != null) {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }

        updateDeviceState(STATE_DISCONNECTED);
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     *
     * @param out The bytes to write
     * @see ConnectedThread#write(byte[])
     */
    private boolean write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return false;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
        return true;
    }

    /**
     * Erases the Boogie Board Sync's screen.
     *
     * @return an immediate check if the message could be sent.
     */
    public boolean eraseSync() {
        if (mState != STATE_CONNECTED) return false;

        if (DEBUG) Log.log(Level.INFO, "writing message to erase Boogie Board Sync's screen");

        // Clean up paths.
        mPaths.clear();

        // Create the HID message to be sent to the Sync to erase the screen.
        byte ERASE_MODE = 0x01;
        HIDSetReport setReport = new HIDSetReport(HIDSetReport.TYPE_FEATURE, HIDSetReport.ID_OPERATION_REQUEST, new byte[]{ERASE_MODE});

        return write(setReport.getPacketBytes());
    }

    /**
     * Updates the Boogie Board Sync's local time with the time of the device currently connected to it.
     *
     * @return an immediate check if the message could be sent.
     */
    private boolean updateSyncTimeWithLocalTime() {
        if (mState != STATE_CONNECTED) return false;

        // Construct the byte array for the time.
        Calendar calendar = Calendar.getInstance();
        int second = calendar.get(Calendar.SECOND) / 2;
        int minute = calendar.get(Calendar.MINUTE);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        int month = calendar.get(Calendar.MONTH) + 1;
        int year = calendar.get(Calendar.YEAR) - YEAR_OFFSET;

        byte byte1 = (byte) ((minute << 5) | second);
        byte byte2 = (byte) ((hour << 3) | (minute >> 3));
        byte byte3 = (byte) ((month << 5) | day);
        byte byte4 = (byte) ((year << 1) | (month >> 3));

        // Create the HID message to be sent to the Sync to set the time.
        HIDSetReport setReport = new HIDSetReport(HIDSetReport.TYPE_FEATURE, HIDSetReport.ID_DATE, new byte[]{byte1, byte2, byte3,
                byte4});
        if (DEBUG) Log.log(Level.INFO, "writing message to update Boogie Board Sync's time");
        return write(setReport.getPacketBytes());
    }

    /**
     * Sets the Boogie Board Sync into the specified mode.
     *
     * @param mode to put the Boogie Board Sync in.
     * @return an immediate check if the message could be sent.
     */
    public boolean setSyncMode(int mode) {
        // Check to see if a valid mode was sent.
        if (mMode == mode || mode < MODE_NONE || mode > MODE_FILE || mState != STATE_CONNECTED)
            return false;

        // Create the HID message to be sent to the Sync to change its mode.
        HIDSetReport setReport = new HIDSetReport(HIDSetReport.TYPE_FEATURE, HIDSetReport.ID_MODE, new byte[]{(byte) mode});
        if (DEBUG) Log.log(Level.INFO, "writing message to set Boogie Board Sync into different mode");
        if (write(setReport.getPacketBytes())) {
            mMode = mode;
            return true;
        } else {
            return false;
        }
    }

    public List<String> getPairedDevices() {
        return devicesUrlList;
    }

    /**
     * Returns a list of paths that the Sync currently have drawn on it.
     *
     * @return paths
     */
    public List<SyncPath> getPaths() {
        return mPaths;
    }

    /**
     * Tells the Boogie Board Sync what device is currently connected to it.
     *
     * @return an immediate check if the message could be sent.
     */
    private boolean informSyncOfDevice() {
        if (mState != STATE_CONNECTED) return false;

        // Create the HID message to be sent to the Sync to tell the Sync what device this is.
        byte ANDROID_DEVICE = 8;
        HIDSetReport setReport = new HIDSetReport(HIDSetReport.TYPE_FEATURE, HIDSetReport.ID_DEVICE, new byte[]{ANDROID_DEVICE, 0x00,
                0x00, 0x00});
        if (DEBUG) Log.log(Level.INFO, "writing message to inform Boogie Board Sync what device we are");
        return write(setReport.getPacketBytes());
    }
    
    /**
     * Find paired Boogie Board Sync devices
     */
    private void findPairedDevices() {
        if (DEBUG) Log.log(Level.INFO, "searching for paired Syncs");
        ServicesSearch serviceSearch = new ServicesSearch();
        Map<String, List<String>> devicesMap = serviceSearch.getBluetoothDevices();

        devicesUrlList.clear();
        for (String key : devicesMap.keySet()) {
            List<String> deviceInfo = devicesMap.get(key);
            if (deviceInfo.get(0).equals("Sync")) {
                if (DEBUG) Log.log(Level.INFO, "found a Boogie Board Sync");
                String[] sa = deviceInfo.get(2).split("\n");
                devicesUrlList.add(sa[1]);
            }
        }
    }

    /**
     * Adds a listener to the Sync streaming service. Listener is used for state changes and asynchronous callbacks from streaming commands.
     * Remember to remove
     * the listener with {@link #removeListener(SyncStreamingListener)} when finished.
     *
     * @param listener Class that implements SyncStreamingListener for asynchronous callbacks.
     * @return false indicates listener has already been added
     */
    public boolean addListener(SyncStreamingListener listener) {
        if (mListeners.contains(listener)) return false;
        else mListeners.add(listener);
        return true;
    }

    /**
     * Removes a listener that was previously added with {@link #addListener(SyncStreamingListener)}.
     *
     * @param listener Class that implements SyncStreamingListener for asynchronous callbacks.
     * @return false indicates listener was not originally added
     */
    public boolean removeListener(SyncStreamingListener listener) {
        if (!mListeners.contains(listener)) return false;
        else mListeners.remove(listener);
        return true;
    }

    private void updateDeviceState(int newState) {
        if (newState == mState) return;
        if (DEBUG) Log.log(Level.INFO, "device state changed from " + mState + " to " + newState);

        int oldState = mState;
        mState = newState;

        // Clean up objects when there is a disconnection.
        if (newState == STATE_DISCONNECTED) {
            // Reset the mode of the Boogie Board Sync.
            mMode = MODE_NONE;
            mPaths.clear();

            if (oldState == STATE_CONNECTED) showDisconnectionNotification();
        } else if (newState == STATE_CONNECTED) {
            setSyncMode(MODE_FILE);
            updateSyncTimeWithLocalTime();
            informSyncOfDevice();
            showConnectionNotification(true);
        }

        for (SyncStreamingListener listener : mListeners) {
            listener.onStreamingStateChange(oldState, newState);
        }
    }
    
    /**
     * Handle message class which hacks the Android version
     */
    private class MessageHandler {
        public void handleMessage(int what, Object obj, int arg1) {
            // Parse the message that was returned from the background thread.
            if (what == MESSAGE_DATA) {
                byte[] buffer = (byte[]) obj;
                int numBytes = arg1;

                List<HIDMessage> hidMessages = HIDUtilities.parseBuffer(buffer, numBytes);

                if (hidMessages == null) return;

                // Received a capture report.
                for (HIDMessage hidMessage : hidMessages) {
                    if (hidMessage == null) {
                        Log.log(Level.WARNING, "was unable to parse the returned message from the Sync");
                    } else if (hidMessage instanceof SyncCaptureReport) {
                        SyncCaptureReport captureReport = (SyncCaptureReport) hidMessage;
                        for (SyncStreamingListener listener : mListeners) listener.onCaptureReport(captureReport);

                        // Filter the paths that are returned from the Boogie Board Sync.
                        List<SyncPath> paths = Filtering.filterSyncCaptureReport(captureReport);
                        if (paths.size() > 0) {
                            for (SyncStreamingListener listener : mListeners) listener.onDrawnPaths(paths);
                            mPaths.addAll(paths);
                        }

                        // Erase button was pushed.
                        if (captureReport.hasEraseSwitchFlag()) {
                            mPaths.clear();
                            for (SyncStreamingListener listener : mListeners) listener.onErase();
                        }

                        // Save button was pushed.
                        if (captureReport.hasSaveFlag()) {
                            for (SyncStreamingListener listener : mListeners) listener.onSave();
                        }
                    }
                }
            }

            // Connected to a device from the accept or connect thread.
            // Passed object will be a socket.
            else if (what == MESSAGE_CONNECTED) {
                connected((StreamConnection) obj);
            }

            // Disconnected from the device on a worker thread.
            else if (what == MESSAGE_CONNECTION_BROKEN) {
                // Update the state of the device, want to show the disconnection notification and then pop into listening mode since the accept
                // thread should still be running.
                updateDeviceState(STATE_DISCONNECTED);
                updateDeviceState(STATE_LISTENING);
            }
        }
    }

    /**
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It runs until a connection is accepted
     * (or until canceled).
     */
    private class AcceptThread extends Thread {
        // The local server socket
        private final StreamConnectionNotifier streamConnNotifier;

        public AcceptThread() {
            StreamConnectionNotifier tmp = null;

            // Create a new listening server socket.
            try {
                String connectionString = "btspp://localhost:" + LISTEN_UUID + ";name=Sync Streaming Profile";
                tmp = (StreamConnectionNotifier) Connector.open(connectionString);
            } catch (IOException e) {
                Log.log(Level.INFO, "listen() failed", e);
            }
            streamConnNotifier = tmp;
        }

        public void run() {
            // Server socket could be null if Bluetooth was turned off and it threw an IOException
            if (streamConnNotifier == null) {
                Log.log(Level.SEVERE, "server socket is null, finish the accept thread");
                return;
            }

            if (DEBUG) Log.log(Level.INFO, "BEGIN mAcceptThread" + this);
            setName("AcceptThread");

            StreamConnection connection;
            while (true) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    System.out.println("\nListener Started. Waiting for Sync to connect...");
                    connection = streamConnNotifier.acceptAndOpen();
                } catch (IOException e) {
                    Log.log(Level.SEVERE, "accept() failed", e);
                    break;
                }

                // If a connection was accepted
                if (connection != null) {
                    synchronized (SyncStreamingService.this) {
                        // Normal operation.
                        if (mState == STATE_LISTENING || mState == STATE_DISCONNECTED) {
                            mMessageHandler.handleMessage(MESSAGE_CONNECTED, connection, 0);
                        }

                        // Either not ready or already connected. Terminate new socket.
                        else if (mState == STATE_CONNECTED) {
                            try {
                                connection.close();
                            } catch (IOException e) {
                                Log.log(Level.SEVERE, "Could not close unwanted socket", e);
                            }
                        }
                    }
                }
            }
            if (DEBUG) Log.log(Level.INFO, "END mAcceptThread");
        }

        public void cancel() {
            if (DEBUG) Log.log(Level.INFO, "cancel " + this);
            try {
                if (streamConnNotifier != null) streamConnNotifier.close();
            } catch (IOException e) {
                Log.log(Level.SEVERE, "close() of server failed", e);
            }
        }
    }

    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final StreamConnection streamConnection;

        public ConnectThread(String connectionURL) {
            StreamConnection tmp = null;
            
            // Get a BluetoothSocket for a connection with the given BluetoothDevice
            try {
                //tmp = device.createInsecureRfcommSocketToServiceRecord(CONNECT_UUID);
                tmp = (StreamConnection) Connector.open(connectionURL);
            } catch (IOException e) {
                Log.log(Level.SEVERE, "create() failed", e);
            }
            streamConnection = tmp;
        }

        public void run() {
            Log.log(Level.INFO, "BEGIN mConnectThread");
            setName("ConnectThread");
            
            // Reset the ConnectThread because we're done
            synchronized (SyncStreamingService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            mMessageHandler.handleMessage(MESSAGE_CONNECTED, streamConnection, -1);
        }

        public void cancel() {
            try {
                streamConnection.close();
            } catch (IOException e) {
                Log.log(Level.SEVERE, "close() of connect socket failed", e);
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final StreamConnection streamConnection;
        private final InputStream mInputStream;
        private final OutputStream mOutputStream;

        public ConnectedThread(StreamConnection conn) {
            Log.log(Level.INFO, "create ConnectedThread: ");
            streamConnection = conn;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = streamConnection.openInputStream();
                tmpOut = streamConnection.openOutputStream();
            } catch (IOException e) {
                Log.log(Level.SEVERE, "temp sockets not created", e);
            }

            mInputStream = tmpIn;
            mOutputStream = tmpOut;
        }

        public void run() {
            Log.log(Level.INFO, "BEGIN mConnectedThread");
            byte[] buffer = new byte[1024];
            int bytes;

            // Keep listening to the InputStream while connected
            while (true) {
                try {
                    // Read from the InputStream
                    bytes = mInputStream.read(buffer);

                    // Send the obtained bytes to the main thread to be processed.
                    mMessageHandler.handleMessage(MESSAGE_DATA, buffer, bytes);

                    // Reset buffer.
                    buffer = new byte[1024];
                } catch (IOException e) {
                    mMessageHandler.handleMessage(MESSAGE_CONNECTION_BROKEN, null, -1);
                    if (DEBUG) Log.log(Level.INFO, "disconnected", e);
                    break;
                }
            }
        }

        /**
         * Write to the connected OutputStream.
         *
         * @param buffer The bytes to write
         */
        public void write(byte[] buffer) {
            try {
                mOutputStream.write(buffer);
                mOutputStream.flush();
            } catch (IOException e) {
                Log.log(Level.SEVERE, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                streamConnection.close();
            } catch (IOException e) {
                Log.log(Level.SEVERE, "close() of connect socket failed", e);
            }
        }
    }

    private void showConnectionNotification(boolean showTicker) {
        System.out.println("Connected");
    }

    private void showDisconnectionNotification() {
        System.out.println("Disconnected");
    }
}