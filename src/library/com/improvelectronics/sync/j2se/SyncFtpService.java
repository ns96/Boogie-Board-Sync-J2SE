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

import com.improvelectronics.sync.Config;
import com.improvelectronics.sync.obex.OBEXFtpFolderListingItem;
import com.improvelectronics.sync.obex.OBEXFtpUtils;
import com.javaquery.bluetooth.ServicesSearch;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.bluetooth.UUID;
import javax.microedition.io.Connector;
import javax.obex.ClientSession;
import javax.obex.HeaderSet;
import javax.obex.Operation;
import javax.obex.ResponseCodes;

/**
 * This service connects to the Boogie Board Sync devices and communicates with the Sync using the OBEX Bluetooth File Transfer protocol. All of the
 * connections are done automatically since this service is always running while Bluetooth is enabled. A client of this service can add a listener,
 * that implements SyncFtpListener, to listen for changes of the service as well as send FTP commands to the connected Boogie Board Sync.
 * <p/>
 * Currently, this service only allows one device to be connected at a time and only one client may send FTP commands at a time. When a client is
 * finished using FTP make sure to call {@link #disconnect() disconnect}.
 * 
 * 
 * @modified Nathan Stevens
 * @date 10/23/2004
 * 
 * This is a port to J2SE using the Bluecove library so all the Android specific
 * stuff has been removed.  Moreover, the code is simplified since the Bluecove
 * library implements methods which make working with Obex FTP
 * easier than Android
 */

public class SyncFtpService {
    private static final Logger Log = Logger.getLogger(SyncFtpService.class.getName());
    
    private static final UUID FTP_UUID = new UUID(0x1106);
 
    private int mState;
    private Long mConnectionId;
    private List<SyncFtpListener> mListeners;
    private static final boolean DEBUG = Config.DEBUG;
    private URI mDirectoryUri;
    private File storeDirectory;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private ArrayList<String> devicesUrlList;
    private static final String ACTION_BASE = "com.improvelectronics.sync.android.SyncFtpService.action";

    /**
     * Broadcast Action: The state of the Sync FTP service changed.
     */
    public static final String ACTION_STATE_CHANGED = ACTION_BASE + ".STATE_CHANGED";

    /**
     * Used as an int extra field in {@link #ACTION_STATE_CHANGED ACTION_STATE_CHANGED} intents for current state.
     */
    public static final String EXTRA_STATE = "EXTRA_STATE";

    /**
     * Used as an int extra field in {@link #ACTION_STATE_CHANGED ACTION_STATE_CHANGED} intents for previous state.
     */
    public static final String EXTRA_PREVIOUS_STATE = "EXTRA_PREVIOUS_STATE";

    // Communication with background thread.
    private MessageHandler mMessageHandler;
    
    private static final int MESSAGE_CONNECTED = 14;
    private static final int MESSAGE_CONNECTION_BROKEN = 15;
    private static final int MESSAGE_ACTION = 16;
    private static final int MESSAGE_STATE = 17;

    private static final int ACTION_CONNECT = 1;
    private static final int ACTION_DISCONNECT = 2;
    private static final int ACTION_PUT = 3;
    private static final int ACTION_SET_PATH = 4;
    private static final int ACTION_GET_FILE = 5;
    private static final int ACTION_GET_DIRECTORY = 6;

    /**
     * The Sync FTP service is in connected state.
     */
    public static final int STATE_CONNECTED = 0;

    /**
     * The Sync FTP service is in connecting state.
     */
    public static final int STATE_CONNECTING = 1;

    /**
     * The Sync FTP service is in disconnected state.
     */
    public static final int STATE_DISCONNECTED = 2;

    /**
     * Indicates a FTP command was successful.
     */
    public static final int RESULT_OK = 0;

    /**
     * Indicates a FTP command was not successful.
     */
    public static final int RESULT_FAIL = -1;

    public SyncFtpService(String syncURL, File storeDirectory) {
        this.storeDirectory = storeDirectory;
        
        // Set the default properties.
        mState = STATE_DISCONNECTED;
        mMessageHandler = new MessageHandler();
        mListeners = new ArrayList<>();
        devicesUrlList = new ArrayList<>();
        mConnectionId = -1L;
        
        if(syncURL == null) {
            findPairedDevices();
        } else {
            connect(syncURL);
        }
    }

    /**
     * Adds a listener to the Sync FTP service. Listener is used for state changes and asynchronous callbacks from FTP commands. Remember to remove
     * the listener with {@link #removeListener(SyncFtpListener)} when finished.
     *
     * @param listener Class that implements SyncFtpListener for asynchronous callbacks.
     * @return false indicates listener has already been added
     */
    public boolean addListener(SyncFtpListener listener) {
        if (mListeners.contains(listener)) return false;
        else mListeners.add(listener);
        return true;
    }

    /**
     * Removes a listener that was previously added with {@link #addListener(SyncFtpListener)}.
     *
     * @param listener Class that implements SyncFtpListener for asynchronous callbacks.
     * @return false indicates listener was not originally added
     */
    public boolean removeListener(SyncFtpListener listener) {
        if (!mListeners.contains(listener)) return false;
        else mListeners.remove(listener);
        return true;
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     *
     * @param device The BluetoothDevice to connect
     */
    private synchronized void connect(String connectionUrl) {
        if (DEBUG) Log.log(Level.INFO, "connect to: {0}", connectionUrl);

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
        mConnectThread = new ConnectThread(connectionUrl);
        mConnectThread.start();
        updateDeviceState(STATE_CONNECTING);
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection.
     *
     * @param socket The BluetoothSocket on which the connection was made
     */
    private synchronized void connected(ClientSession clientSession) {
        if (DEBUG) Log.log(Level.INFO, "connected");

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        updateDeviceState(STATE_CONNECTED);
        
        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(clientSession);
        mConnectedThread.start();
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

        updateDeviceState(STATE_DISCONNECTED);
    }

    private void findPairedDevices() {
        if (DEBUG) Log.log(Level.INFO, "searching for paired Syncs");
        ServicesSearch serviceSearch = new ServicesSearch();
        Map<String, List<String>> devicesMap = serviceSearch.getBluetoothDevices(ServicesSearch.OBEX_FILE_TRANSFER_PROFILE);

        devicesUrlList.clear();
        for (String key : devicesMap.keySet()) {
            List<String> deviceInfo = devicesMap.get(key);
            if (deviceInfo.get(0).equals("Sync")) {
                if (DEBUG) Log.log(Level.INFO, "found a Boogie Board Sync");
                String[] sa = deviceInfo.get(2).split("\n");
                devicesUrlList.add(sa[1]);
            }
        }

        // Connect to the first device that was found in the list of paired devices.
        if (!devicesUrlList.isEmpty() && mState != STATE_CONNECTED) {
            connect(devicesUrlList.get(0));
        }
    }

    /**
     * Disconnect from the FTP server. This is required if a connection was established with {@link #connect()}. This is an asynchronous call.
     *
     */
    public void disconnect() {
        if (mState == STATE_CONNECTED) {
            mConnectedThread.cancel();
            mState = STATE_DISCONNECTED;
        }
    }

    /**
     * Request the current folder listing of the connected Boogie Board Sync. This is an asynchronous call.
     *
     * @param folderName
     * @return immediate check if the command could be sent
     */
    public void listFolder(String folderName) {
        if (mState == STATE_CONNECTED) {
            mConnectedThread.listFolder(folderName);
        }
    }
    
    /**
     * Changes the current folder of the connected Boogie Board Sync. This is an
     * asynchronous call.
     *     
* @param folderName Name of the folder to change to. A blank ("") folder
     * name will change to the root directory. Providing ".." will change the
     * directory to the parent directory.
     */
    public void changeFolder(String folderName) {
        if (mState == STATE_CONNECTED && folderName != null) {
            mConnectedThread.changeFolder(folderName);
        }
    }


    /**
     * Delete the specified file/folder from the Boogie Board Sync. This is an asynchronous call.
     *
     * @param fileName Name of the file to be deleted
     */
    public void deleteFile(String fileName) {
        if (mState == STATE_CONNECTED) {
            mConnectedThread.deleteFile(fileName);
        }
    }

    /**
     * Get a file from the Boogie Board Sync. This is an asynchronous call.
     *
     * @param fileName File to be retrieved
     */
    public void getFile(String fileName) {
        if (mState == STATE_CONNECTED) {
            mConnectedThread.getFile(fileName);
        }
    }

    /**
     * Returns the current directory of the Boogie Board Sync. This will return null if there is no current connection to a Boogie Board Sync.
     *
     * @return uri of the current directory.
     */
    public URI getDirectoryUri() {
        return mDirectoryUri;
    }

    /**
     * Returns the current state of the Sync FTP service.
     *
     * @return state
     */
    public int getState() {
        return mState;
    }

    /**
     * Returns the currently connected {@link BluetoothDevice}.
     *
     * @return device that is connected, returns null if there is no device connected
     */
    public String getConnectedDevice() {
        if (mState != STATE_CONNECTED) return null;
        else return devicesUrlList.get(0);
    }

    private void updateDeviceState(int newState) {
        if (newState == mState) return;
        if (DEBUG) Log.log(Level.INFO, "Device state changed from " + mState + " to " + newState);

        int oldState = mState;
        mState = newState;

        for (SyncFtpListener listener : mListeners) {
            listener.onFtpDeviceStateChange(oldState, newState);
        }
    }

    private class MessageHandler {
        public void handleMessage(int what, Object obj, int arg1, int arg2) {
            // Parse the message that was returned from the background thread.
            if (what == MESSAGE_ACTION) {
                int action = arg1;
                int result = arg2;

                // CONNECT action, returns an object with the connection id.
                if (action == ACTION_CONNECT) {
                    if (result == RESULT_OK) {
                        mConnectionId = (Long) obj;
                    }

                    for (SyncFtpListener listener : mListeners) listener.onConnectComplete(result);
                }

                // DISCONNECT action.
                else if (action == ACTION_DISCONNECT) {
                    for (SyncFtpListener listener : mListeners) listener.onDisconnectComplete(result);
                }

                // PUT action.
                else if (action == ACTION_PUT) {
                    if (result == RESULT_OK) {
                        String fileName = (String)obj;
                        for (SyncFtpListener listener : mListeners) listener.onDeleteComplete(fileName, RESULT_OK);
                    } else {
                        for (SyncFtpListener listener : mListeners) listener.onDeleteComplete(null, RESULT_FAIL);
                    }
                }

                // SET_PATH action, returns an object of the updated file path.
                else if (action == ACTION_SET_PATH) {
                    if (result == RESULT_OK) {
                        String filePath = (String) obj;

                        if (filePath.equals("/")) {
                            mDirectoryUri = URI.create("/");
                        } else {
                            mDirectoryUri = mDirectoryUri.resolve(filePath);
                        }

                        for (SyncFtpListener listener : mListeners) listener.onChangeFolderComplete(mDirectoryUri, RESULT_OK);
                    } else {
                        for (SyncFtpListener listener : mListeners) listener.onChangeFolderComplete(null, RESULT_FAIL);
                    }
                }

                // GET_FILE action, returns a byte array of the file data retrieved.
                else if (action == ACTION_GET_FILE) {
                    if (result == RESULT_OK) {
                        File file = (File) obj;
                        for (SyncFtpListener listener : mListeners) listener.onGetFileComplete(file, RESULT_OK);
                    } else {
                        for (SyncFtpListener listener : mListeners) listener.onGetFileComplete(null, RESULT_FAIL);
                    }
                }

                // GET_FILE action, returns a byte array of the directory listing.
                else if (action == ACTION_GET_DIRECTORY) {
                    if (result == RESULT_OK) {
                        List<OBEXFtpFolderListingItem> directory = OBEXFtpUtils.parseXML((String)obj);
                        for (SyncFtpListener listener : mListeners) listener.onFolderListingComplete(directory, RESULT_OK);
                    } else {
                        for (SyncFtpListener listener : mListeners) listener.onFolderListingComplete(null, RESULT_FAIL);
                    }
                }
            }

            // Connected to a device from the connect thread.
            // Passed object will be a socket.
            else if (what == MESSAGE_CONNECTED) {
                connected((ClientSession) obj);
            }

            // Disconnected from the device on a worker thread.
            else if (what == MESSAGE_CONNECTION_BROKEN) {
                // Update the state of the device.
                updateDeviceState(STATE_DISCONNECTED);
            } else if (what == MESSAGE_STATE) {
                updateDeviceState(STATE_CONNECTED);
            }
        }
    }

    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final ClientSession clientSession;

        public ConnectThread(String connectionURL) {
            ClientSession tmp = null;

            // Get a BluetoothSocket for a connection with the
            // given BluetoothDevice
            try {
                tmp = (ClientSession) Connector.open(connectionURL);
                mDirectoryUri = new URI("/");
            } catch (IOException e) {
                Log.log(Level.SEVERE, "connection failed", e);
            } catch (URISyntaxException ex) {
                Log.log(Level.SEVERE, null, ex);
            }
            clientSession = tmp;
        }

        public void run() {
            Log.log(Level.INFO, "BEGIN mConnectThread");
            setName("ConnectThread");
            
            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception. Test we can open an output stream
                HeaderSet header = clientSession.createHeaderSet();
                header.setHeader(HeaderSet.TARGET, OBEXFtpUtils.OBEX_FTP_UUID);
                HeaderSet response = clientSession.connect(header);
                
                if(response.getResponseCode() == ResponseCodes.OBEX_HTTP_OK) {
                    Long connectionID = clientSession.getConnectionID();
                    mMessageHandler.handleMessage(MESSAGE_ACTION, connectionID, ACTION_CONNECT, RESULT_OK);
                    if(DEBUG) Log.log(Level.INFO, "Connected to Sync FTP server");
                } else {
                    mMessageHandler.handleMessage(MESSAGE_ACTION, null, ACTION_CONNECT, RESULT_OK);
                    Log.log(Level.INFO, "Error Connecting to Sync FTP server " + 
                            response.getResponseCode());
                }
            } catch (IOException e) {
                // Close the socket
                try {
                    clientSession.close();
                } catch (IOException e2) {
                    Log.log(Level.SEVERE, "unable to close() socket during connection failure", e2);
                }
                mMessageHandler.handleMessage(MESSAGE_CONNECTION_BROKEN, null, -1, -1);
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (SyncFtpService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            mMessageHandler.handleMessage(MESSAGE_CONNECTED, clientSession, -1, -1);
        }
        
        /**
         * Method to close the connection
         */
        public void cancel() {
            try {
                clientSession.disconnect(clientSession.createHeaderSet());
                clientSession.close();
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
        private final ClientSession clientSession;

        public ConnectedThread(ClientSession conn) {
            Log.log(Level.INFO, "create ConnectedThread: ");
            clientSession = conn;
        }
        
        /**
         * Start the thread
         */
        public void run() {
            Log.log(Level.INFO, "BEGIN mConnectedThread");
        }
        
        /**
         * Method to list the records in a folder
         * @param folder 
         */
        public void listFolder(String folderName) {
            try {
                //Go the desired folder
                HeaderSet header = clientSession.createHeaderSet();
                header.setHeader(HeaderSet.NAME, folderName);
                HeaderSet result = clientSession.setPath(header, false, false);
                
                if(result.getResponseCode() == ResponseCodes.OBEX_HTTP_OK) {
                    //Retreive the contents of the folder
                    header = clientSession.createHeaderSet();
                    header.setHeader(HeaderSet.TYPE, OBEXFtpUtils.FOLDER_LISTING_TYPE);
                    
                    Operation op = clientSession.get(header);
                    BufferedReader br = new BufferedReader(new InputStreamReader(op.openInputStream()));
                    
                    String line;
                    String xmlString = "";
                    
                    while ((line = br.readLine()) != null) {
                        xmlString = xmlString + line + "\n";
                    }
                    
                    // close the streams
                    br.close();
                    op.close();
                    
                    // call the message handeler
                    mMessageHandler.handleMessage(MESSAGE_ACTION, xmlString, ACTION_GET_DIRECTORY, RESULT_OK);
                    if(DEBUG) Log.log(Level.INFO, "Obex XML String:\n" + xmlString);
                } else {
                    mMessageHandler.handleMessage(MESSAGE_ACTION, null, ACTION_GET_DIRECTORY, RESULT_FAIL);
                    Log.log(Level.SEVERE, "Unable to change to " + folderName);
                }
            } catch (IOException ex) {
                Log.log(Level.SEVERE, null, ex);
            }
        }
        
        /**
         * Delete a file from the sync
         * @param fileName 
         */
        private void deleteFile(String fileName) {
            try {
                //Go the desired folder
                HeaderSet header = clientSession.createHeaderSet();
                header.setHeader(HeaderSet.NAME, fileName);
                
                HeaderSet response = clientSession.delete(header);
                
                if (response.getResponseCode() == ResponseCodes.OBEX_HTTP_OK) {
                    if(DEBUG) Log.log(Level.INFO, "deleted file");
                    mMessageHandler.handleMessage(MESSAGE_ACTION, fileName, ACTION_PUT, RESULT_OK);
                } else {
                    mMessageHandler.handleMessage(MESSAGE_ACTION, fileName, ACTION_PUT, RESULT_FAIL);
                }                
            }   catch (IOException ex) {
                Log.log(Level.SEVERE, null, ex);
            }
        }
        
        /**
         * Method to get a file from the BBSync
         * @param fileName 
         */
        private void getFile(String fileName) {
            try {
                //Go the desired folder
                HeaderSet header = clientSession.createHeaderSet();
                header.setHeader(HeaderSet.NAME, fileName);
                
                Operation op = clientSession.get(header);
                InputStream is = op.openInputStream();
                
                File file = new File(storeDirectory, fileName);
                FileOutputStream fos = new FileOutputStream(file);
                
                byte b[] = new byte[1024];
                int len;
                
                while (is.available() > 0 && (len = is.read(b)) > 0) {
                    fos.write (b, 0, len);
                }
                
                // close the streams
                fos.close();
                is.close();
                
                mMessageHandler.handleMessage(MESSAGE_ACTION, file, ACTION_GET_FILE, RESULT_OK);
                if(DEBUG)Log.log(Level.INFO, "File stored in: {0}", file.getAbsolutePath());
            } catch (IOException ex) {
                mMessageHandler.handleMessage(MESSAGE_ACTION, null, ACTION_GET_FILE, RESULT_FAIL);
                Log.log(Level.SEVERE, null, ex);
            }
        }
        
        /**
         * Method to list folder
         * 
         * @param folderName 
         */
        private void changeFolder(String folderName) {
            try {
                //Go the desired folder
                HeaderSet header = clientSession.createHeaderSet();
                header.setHeader(HeaderSet.NAME, folderName);
                HeaderSet result = clientSession.setPath(header, false, false);
                
                if(result.getResponseCode() == ResponseCodes.OBEX_HTTP_OK) {
                    mMessageHandler.handleMessage(MESSAGE_ACTION, folderName, ACTION_SET_PATH, RESULT_OK);
                } else {
                    mMessageHandler.handleMessage(MESSAGE_ACTION, folderName, ACTION_SET_PATH, RESULT_FAIL);
                }
            }   catch (IOException ex) {
                Log.log(Level.SEVERE, null, ex);
            }
        }
        
        /**
         * Method to close the connection
         * @return 
         */
        public void cancel() {
            try {
                clientSession.disconnect(clientSession.createHeaderSet());
                clientSession.close();
                mMessageHandler.handleMessage(MESSAGE_ACTION, null, ACTION_DISCONNECT, RESULT_OK);
            } catch (IOException e) {
                mMessageHandler.handleMessage(MESSAGE_ACTION, null, ACTION_DISCONNECT, RESULT_FAIL);
                Log.log(Level.SEVERE, "close() of connect socket failed", e);
            }
        }
    }
}