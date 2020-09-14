# Soar Language Server for Eclipse

Eclipse support for LSPs is built into recent versions of Eclipse (this was tested on 2020-06).

## Setup

Follow these steps in Eclipse:

1. Open Window-->Preferences
1. Go to General-->Content Types
1. Click on Text
1. Click on "Add Child..."
1. Set Name to "Soar Source File"
1. Click OK
1. Next to File associations click "Add..."
1. For Content type, enter `*.soar`
1. Repeat the last two steps with `*.tcl`
1. Click "Apply and close"
1. Go to Run-->External Tools-->External Tools Configurations...
1. Click on Program
1. Press the "New Configuration" button
1. Change the Name to "Soar Language Server"
1. Under Location, click on "Browse File System..."
1. Select "soar-language-server.bat" from the soar-language-server distribution
1. Click "Apply"
1. Click "Close"
1. Go back to Window-->Preferences
1. Click on Language Servers
1. Click "Add..."
1. Under "Associate content-type..." select Text-->Soar Source File
1. Under "...with Language Server Launch Configuration" select Program-->Soar
1. Language Server
1. Click "OK"
1. Click "Apply and Close"
