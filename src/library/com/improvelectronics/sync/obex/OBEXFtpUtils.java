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

package com.improvelectronics.sync.obex;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public class OBEXFtpUtils {
    private static final Logger Log = Logger.getLogger(OBEXFtpUtils.class.getName());

    /* UUIDS */
    // OBEX FTP UUID = F9EC7BC4-953C-11d2-984E-525400DC9E09
    public static final byte[] OBEX_FTP_UUID = {(byte) 0xF9, (byte) 0xEC, (byte) 0x7B, (byte) 0xC4, (byte) 0x95,
            (byte) 0x3C, (byte) 0x11, (byte) 0xD2, (byte) 0x98, (byte) 0x4E, (byte) 0x52, (byte) 0x54, (byte) 0x00,
            (byte) 0xDC, (byte) 0x9E, (byte) 0x09};
    /* MIME TYPES */
    // "x-bluetooth/folder-listing" and null terminator
    public static final String FOLDER_LISTING_TYPE = "x-obex/folder-listing";

    /**
     * Converts a OBEX server response to an ArrayList of Items
     *
     * @param response - Response from the server.
     * @return ArrayList<OBEXFtpFolderListingItem>
     */

    public static ArrayList<OBEXFtpFolderListingItem> parseXML(String rawXML) {        
        // remove the dtd stuff otherwise the parser throws an exception
        rawXML = rawXML.replace("<!DOCTYPE folder-listing SYSTEM \"obex-folder-listing.dtd\">", "");
        
        // get the factory
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        Document dom = null;

        try {
            // Using factory get an instance of document builder
            DocumentBuilder db = dbf.newDocumentBuilder();

            // parse using builder to get DOM representation of the XML file
            dom = db.parse(new InputSource(new StringReader(rawXML)));

        } catch (ParserConfigurationException pce) {
            pce.printStackTrace();
        } catch (SAXException se) {
            se.printStackTrace();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }

        if (dom != null)
            return parseDocument(dom);

        return null;
    }

    /**
     * Parses the XML document and returns an array list of items.
     *
     * @param d Document of XML
     * @return ArrayList<OBEXFtpFolderListingItem>
     */

    public static ArrayList<OBEXFtpFolderListingItem> parseDocument(Document d) {
        ArrayList<OBEXFtpFolderListingItem> bluetoothFtpFolderListingItems = new ArrayList<OBEXFtpFolderListingItem>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd'T'HHmmss");

        Element docEle = d.getDocumentElement();

        // Make this code more efficient possibly another method call
        NodeList nl = docEle.getElementsByTagName("folder");
        try {
            if (nl != null && nl.getLength() > 0) {
                for (int i = 0; i < nl.getLength(); i++) {
                    // get the the folder element
                    Element el = (Element) nl.item(i);
                    String name = el.getAttribute("name");
                    Date time = null;

                    if (el.getAttribute("modified").compareTo("") != 0) {
                        time = sdf.parse(el.getAttribute("modified"));
                    } else if (el.getAttribute("created").compareTo("") != 0)
                        time = sdf.parse(el.getAttribute("created"));
                    int size = 0;
                    bluetoothFtpFolderListingItems.add(new OBEXFtpFolderListingItem(name, time, size, new byte[]{}));
                }
            }
            nl = docEle.getElementsByTagName("file");
            if (nl != null && nl.getLength() > 0) {
                for (int i = 0; i < nl.getLength(); i++) {
                    // get the the folder element
                    Element el = (Element) nl.item(i);
                    String name = el.getAttribute("name");
                    Date time = null;
                    if (el.getAttribute("modified").compareTo("") != 0)
                        time = sdf.parse(el.getAttribute("modified"));
                    else if (el.getAttribute("created").compareTo("") != 0)
                        time = sdf.parse(el.getAttribute("created"));
                    int size = Integer.parseInt(el.getAttribute("size"));
                    bluetoothFtpFolderListingItems.add(new OBEXFtpFolderListingItem(name, time, size, new byte[]{}));
                }
            }
        } catch (ParseException e) {
            Log.log(Level.SEVERE, "Error parsing date.");
        }

        // Sort the items by date.
        Collections.sort(bluetoothFtpFolderListingItems, new Comparator<OBEXFtpFolderListingItem>() {
            @Override
            public int compare(OBEXFtpFolderListingItem lhs, OBEXFtpFolderListingItem rhs) {
                // First compare based on type of item. This will rank folders above files.
                if(lhs.getSize() == 0 && rhs.getSize() > 0) return -1;
                else if(lhs.getSize() > 0 && rhs.getSize() == 0) return 1;

                // If the items are the same type then compare based on date.
                if (lhs.getTime().before(rhs.getTime())) {
                    return 1;
                } else if (lhs.getTime().after(rhs.getTime())) {
                    return -1;
                } else {
                    return 0;
                }
            }
        });

        return bluetoothFtpFolderListingItems;
    }
}
