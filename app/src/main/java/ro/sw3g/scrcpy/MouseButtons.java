package ro.sw3g.scrcpy;

/** Independent physical-style button state, shared by the pad and bottom buttons. */
final class MouseButtons {
    static final int LEFT=1,RIGHT=2;
    private int mask;
    int mask(){return mask;}
    boolean change(int button,boolean down){
        if(button!=LEFT&&button!=RIGHT)throw new IllegalArgumentException(I18n.t(R.string.text_041));
        int next=down?mask|button:mask&~button;
        if(next==mask)return false;mask=next;return true;
    }
}
