/*
 * This a a simple command line tool for testing if things work or note
 */

package com.improvelectronics.sync.j2se;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author nathan
 */
public class cmdLineTool {
    /**
     * Method to test and see if things work
     */
    public void testFTP() {
        try {
            String syncURL = "btgoep://0017EC558162:2;authenticate=false;encrypt=false;master=false";
            File storeDirectory = new File("/Users/nathan/temp");
            SyncFtpService ftpService = new SyncFtpService(syncURL, storeDirectory);
            
            // pause a bit to allow things to sync
            Thread.sleep(1000);
            
            ftpService.listFolder("SAVED");
            
            Thread.sleep(2000);
            
            //ftpService.changeFolder("SAVED");
            
            ftpService.getFile("BB_00023.PDF");
            
        } catch (InterruptedException ex) {
            Logger.getLogger(cmdLineTool.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    // Method to test the streaming of data
    public void testStreaming() {
        try {
            String syncURL = "btspp://0017EC558162:2;authenticate=false;encrypt=false;master=false";
            SyncStreamingService service = new SyncStreamingService(syncURL);
            
            Thread.sleep(4000);
            service.setSyncMode(SyncStreamingService.MODE_CAPTURE);
            
            Thread.sleep(2000);
            service.eraseSync();
            
        } catch (InterruptedException ex) {
            Logger.getLogger(cmdLineTool.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    /**
     * The main method
     * @param args 
     */
    public static void main(String[] args) {
        cmdLineTool tool = new cmdLineTool();
        //tool.testFTP();
        tool.testStreaming();
    }
}
