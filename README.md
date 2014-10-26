# Boogie Board Sync SDK 0.1 for J2SE

The software development kit provides a library for communicating with a Boogie Board Sync on J2SE Platforms. It is based largely on the [Andriod SDK from Kent-Displays](https://github.com/kent-displays/boogie-board-sync-android-sdk) and is still very much a work in progress.  This library allows developers to view, modify and retrieve aspects of the file system on the Sync. It also allows developers to retrieve real-time information, like current position of the stylus and a user pressing the save and erase button (**10-26-34 -- Unfortunately this functionality currently doesn't work**).

*Note: This library requires the Bluecove library (http://bluecove.org/). All communication is done using Bluetooth.*

- [Installing](#installing)
- [Configuring](#configuring)
- [Structure](#structure)
- [Documentation](#documentation)
- [Limitations](#limitations)
- [Questions?](#questions)
- [License](#license)

## Installing

Note: This library was developed using the Neatbeans IDE (https://netbeans.org/).

#### Option 1: Download entire project
Download the entire project directory here and then just open it in NetBeans the project into Android Studio. From there you should be able to get up and running with the included samples.

#### Option 2: Include library
To include this library in your project in the library-name.jar file and the bluecove.jar in your class path.

## Configuring

To allow for easy configuration there is a single java class called *Config.java*. Here you can turn on/off debugging for the entire library.

## Structure
This is a quick overview on how the entire library and API are structured for use. **Highly recommend reading this before starting.**

This library is broken up into two essential parts. On one side you have the Streaming API where you can get erase/save button pushes as well as real-time paths that are drawn on the Sync. On the other is the File Transfer API where you can delete, download files from the Sync as well as traverse the internal directory structure of the Sync.


## Documentation

Javadocs for this library can be found [here](#).

## Limitations
There are the following limitations still imposed upon this library.

- Can only communicate with one Boogie Board Sync at a time. This can also cause issues with having more than one Sync paired at a time.

## Questions?

For questions or comments email or contact us on Twitter

- [nathan.stevens@instras.com](mailto:nathan.stevens@instras.com)

## License

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
