/** This Tcl parser originally comes from the SoarIDE. */
package com.soartech.soarls.tcl;

/** @author ray */
public class TclParserError {
  private int start;
  private int length;
  private String message;

  public TclParserError(int offset, int length, String message) {
    super();
    this.start = offset;
    this.length = length;
    this.message = message;
  }

  public int getLength() {
    return length;
  }

  public String getMessage() {
    return message;
  }

  public int getStart() {
    return start;
  }

  @Override
  public String toString() {
    return "[" + start + ", " + length + "): " + message;
  }
}
