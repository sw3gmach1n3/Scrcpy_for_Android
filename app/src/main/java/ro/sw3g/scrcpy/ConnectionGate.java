package ro.sw3g.scrcpy;

/** Controls new connection attempts only; an active Session is never stopped here. */
final class ConnectionGate {
    private boolean foreground,usbBlocked;
    private String approvedUsb;
    void resume(){foreground=true;}
    void pause(){foreground=false;}
    boolean canStart(){return foreground;}
    boolean canAutoUsb(boolean enabled){return foreground&&enabled&&!usbBlocked;}
    void suppressAutoUsb(){usbBlocked=true;}
    void allowAutoUsb(){usbBlocked=false;}
    void approveUsb(String name){approvedUsb=name;}
    String takeApprovedUsb(){if(!foreground)return null;String name=approvedUsb;approvedUsb=null;return name;}
    void detached(String name){if(name==null||name.equals(approvedUsb))approvedUsb=null;usbBlocked=false;}
}
