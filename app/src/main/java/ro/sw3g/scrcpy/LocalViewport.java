package ro.sw3g.scrcpy;

/** Local presentation only: this class never changes the remote device's display. */
final class LocalViewport {
    static final float MAX_ZOOM=6f;
    private float viewW=1,viewH=1,sourceW=1,sourceH=1,zoom=1,left,top,width=1,height=1;
    float zoom(){return zoom;}
    float left(){return left;}
    float top(){return top;}
    float width(){return width;}
    float height(){return height;}
    void size(float vw,float vh,float sw,float sh){
        float centerX=(viewW/2-left)/width,centerY=(viewH/2-top)/height;
        viewW=Math.max(1,vw);viewH=Math.max(1,vh);sourceW=Math.max(1,sw);sourceH=Math.max(1,sh);
        measure();left=viewW/2-centerX*width;top=viewH/2-centerY*height;clamp();
    }
    void reset(){zoom=1;measure();left=(viewW-width)/2;top=(viewH-height)/2;}
    void transform(float factor,float oldX,float oldY,float newX,float newY){
        if(!Float.isFinite(factor)||factor<=0)return;
        float previous=zoom;zoom=Math.max(1,Math.min(MAX_ZOOM,zoom*factor));float ratio=zoom/previous;
        left=newX-(oldX-left)*ratio;top=newY-(oldY-top)*ratio;measure();clamp();
    }
    float sourceX(float x){return Math.max(0,Math.min(1,(x-left)/width));}
    float sourceY(float y){return Math.max(0,Math.min(1,(y-top)/height));}
    private void measure(){float scale=Math.min(viewW/sourceW,viewH/sourceH)*zoom;width=sourceW*scale;height=sourceH*scale;}
    private void clamp(){
        left=width<=viewW?(viewW-width)/2:Math.max(viewW-width,Math.min(0,left));
        top=height<=viewH?(viewH-height)/2:Math.max(viewH-height,Math.min(0,top));
    }
}
