package ro.sw3g.scrcpy;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.util.SparseArray;
import android.view.*;
import android.widget.FrameLayout;
import java.util.function.Consumer;

final class RemoteView extends FrameLayout {
    final TextureView texture;
    private final View cursor;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Consumer<String> log;
    Session session;
    boolean mouse=false;
    boolean buttonMode=false;
    private final MouseButtons held=new MouseButtons();
    private final LocalViewport viewport=new LocalViewport();
    private float initialSpan,lastSpan,focusStartX,focusStartY;
    private boolean localGesture=false,scrollGesture=false;
    float speed=1.2f;
    private float cx=.5f,cy=.5f,lastX,lastY,travel;
    private boolean dragging=false,multi=false;
    private long began;
    private int remoteW=1,remoteH=1;
    private final SparseArray<Point> fingers=new SparseArray<>();
    private final Runnable hold=()->{if(mouse&&!buttonMode&&!multi&&travel<dp(12)&&ready()){dragging=true;mouseEvent(0);}};
    RemoteView(Context c,Consumer<String> log){
        super(c);this.log=log;setBackgroundColor(Color.BLACK);
        texture=new TextureView(c);texture.setOpaque(false);addView(texture,new LayoutParams(-1,-1));
        cursor=new View(c){protected void onDraw(Canvas canvas){if(!mouse)return;RectF r=image();float x=r.left+cx*r.width(),y=r.top+cy*r.height();
            Path p=new Path();p.moveTo(x,y);p.lineTo(x+dp(5),y+dp(20));p.lineTo(x+dp(9),y+dp(13));p.lineTo(x+dp(17),y+dp(12));p.close();
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);canvas.drawPath(p,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));paint.setColor(Color.BLACK);canvas.drawPath(p,paint);paint.setStyle(Paint.Style.FILL);
        }};addView(cursor,new LayoutParams(-1,-1));
        setFocusableInTouchMode(true);
    }
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    void dimensions(int w,int h){releaseTouches();remoteW=w;remoteH=h;fit();}
    protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);fit();}
    private void fit(){
        if(getWidth()==0||getHeight()==0)return;
        viewport.size(getWidth(),getHeight(),remoteW,remoteH);renderViewport();
    }
    private void renderViewport(){
        if(getWidth()==0||getHeight()==0)return;
        Matrix transform=new Matrix();transform.setScale(viewport.width()/getWidth(),viewport.height()/getHeight());
        transform.postTranslate(viewport.left(),viewport.top());texture.setTransform(transform);cursor.invalidate();
    }
    private RectF image(){return new RectF(viewport.left(),viewport.top(),viewport.left()+viewport.width(),viewport.top()+viewport.height());}
    private Point map(float x,float y){return new Point(Math.min(remoteW-1,(int)(viewport.sourceX(x)*remoteW)),Math.min(remoteH-1,(int)(viewport.sourceY(y)*remoteH)));}
    float zoomFactor(){return viewport.zoom();}
    void resetZoom(){viewport.reset();renderViewport();}
    void zoomBy(float factor){if(!mouse)return;viewport.transform(factor,getWidth()/2f,getHeight()/2f,getWidth()/2f,getHeight()/2f);renderViewport();}
    private boolean ready(){return session!=null&&session.control!=null&&session.connected;}
    int mode(){return !mouse?0:buttonMode?2:1;}
    String modeName(){return !mouse?I18n.t(R.string.text_001):buttonMode?I18n.t(R.string.text_002):I18n.t(R.string.text_003);}
    void setMode(int mode){releaseTouches();mouse=mode!=0;buttonMode=mode==2;if(!mouse)resetZoom();cursor.invalidate();log.accept(mode==0?I18n.t(R.string.text_004):mode==1?I18n.t(R.string.text_005):I18n.t(R.string.text_006));}
    void mouseButton(int button,boolean down){
        if(!ready()){held.change(button,false);return;}
        if(held.change(button,down))session.control.mouse(down?0:1,cursorX(),cursorY(),remoteW,remoteH,button,held.mask());
    }
    void clickMouseButton(int button){mouseButton(button,true);mouseButton(button,false);}
    private int cursorX(){return Math.max(0,Math.min(remoteW-1,(int)(cx*remoteW)));}
    private int cursorY(){return Math.max(0,Math.min(remoteH-1,(int)(cy*remoteH)));}
    void releaseTouches(){handler.removeCallbacks(hold);if(ready()){
        for(int i=0;i<fingers.size();i++){Point p=fingers.valueAt(i);session.control.touch(1,fingers.keyAt(i),p.x,p.y,remoteW,remoteH,0,false,0);}
    }mouseButton(MouseButtons.LEFT,false);mouseButton(MouseButtons.RIGHT,false);fingers.clear();dragging=false;localGesture=false;scrollGesture=false;multi=false;}
    private void mouseEvent(int action){
        if(action==0||action==1){mouseButton(MouseButtons.LEFT,action==0);return;}
        if(ready())session.control.mouse(held.mask()==0?7:2,cursorX(),cursorY(),remoteW,remoteH,0,held.mask());
    }
    public boolean onInterceptTouchEvent(MotionEvent event){return true;}
    public boolean onTouchEvent(MotionEvent e){
        if(!ready())return true;
        int action=e.getActionMasked(),index=e.getActionIndex();
        if(!mouse){
            if(action==MotionEvent.ACTION_CANCEL){releaseTouches();return true;}
            if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
                if(!image().contains(e.getX(index),e.getY(index)))return true;
                Point p=map(e.getX(index),e.getY(index));int id=e.getPointerId(index);fingers.put(id,p);
                session.control.touch(0,id,p.x,p.y,remoteW,remoteH,Math.max(.01f,e.getPressure(index)),false,0);
            } else if(action==MotionEvent.ACTION_MOVE){
                for(int i=0;i<e.getPointerCount();i++){int id=e.getPointerId(i);if(fingers.indexOfKey(id)<0)continue;Point p=map(e.getX(i),e.getY(i));fingers.put(id,p);session.control.touch(2,id,p.x,p.y,remoteW,remoteH,Math.max(.01f,e.getPressure(i)),false,0);}
            } else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){int id=e.getPointerId(index);if(fingers.indexOfKey(id)>=0){Point p=map(e.getX(index),e.getY(index));session.control.touch(1,id,p.x,p.y,remoteW,remoteH,0,false,0);fingers.remove(id);}}
            return true;
        }
        if(action==MotionEvent.ACTION_DOWN){
            lastX=e.getX();lastY=e.getY();travel=0;multi=false;localGesture=false;scrollGesture=false;
            began=SystemClock.uptimeMillis();if(!buttonMode)handler.postDelayed(hold,450);
        }else if(action==MotionEvent.ACTION_POINTER_DOWN){
            multi=true;handler.removeCallbacks(hold);if(dragging&&!buttonMode){mouseEvent(1);dragging=false;}
            lastX=focusStartX=centroid(e,true);lastY=focusStartY=centroid(e,false);
            lastSpan=initialSpan=span(e);localGesture=localGesture||viewport.zoom()>1.001f;scrollGesture=false;
        }else if(action==MotionEvent.ACTION_MOVE){
            float x=centroid(e,true),y=centroid(e,false),dx=x-lastX,dy=y-lastY;
            travel+=(float)Math.hypot(dx,dy);
            if(e.getPointerCount()>1){
                float distance=span(e);
                // Pinch and zoomed panning affect only the local TextureView. No touch/zoom packets go to B.
                if(!localGesture&&initialSpan>0&&Math.abs(distance/initialSpan-1)>.04f){
                    localGesture=true;scrollGesture=false;
                    viewport.transform(distance/initialSpan,focusStartX,focusStartY,x,y);renderViewport();
                }else if(localGesture){
                    viewport.transform(lastSpan>0?distance/lastSpan:1,lastX,lastY,x,y);renderViewport();
                }else{
                    if(!scrollGesture&&Math.hypot(x-focusStartX,y-focusStartY)>dp(10)){
                        scrollGesture=true;dx=x-focusStartX;dy=y-focusStartY;
                    }
                    if(scrollGesture)session.control.scroll(cursorX(),cursorY(),remoteW,remoteH,-dx/dp(35),dy/dp(35));
                }
                lastSpan=distance;
            }else if(!localGesture){
                RectF r=image();cx=Math.max(0,Math.min(1,cx+dx/Math.max(1,r.width())*speed));cy=Math.max(0,Math.min(1,cy+dy/Math.max(1,r.height())*speed));
                // Keep the pointer in the visible crop instead of losing it beyond the screen.
                cx=Math.max(viewport.sourceX(0),Math.min(viewport.sourceX(getWidth()),cx));
                cy=Math.max(viewport.sourceY(0),Math.min(viewport.sourceY(getHeight()),cy));cursor.invalidate();mouseEvent(2);
            }
            if(travel>dp(12))handler.removeCallbacks(hold);lastX=x;lastY=y;
        }else if(action==MotionEvent.ACTION_POINTER_UP){
            handler.removeCallbacks(hold);lastX=centroidExcept(e,true,index);lastY=centroidExcept(e,false,index);
            if(e.getPointerCount()>2){lastSpan=initialSpan=spanExcept(e,index);focusStartX=lastX;focusStartY=lastY;}
        }else if(action==MotionEvent.ACTION_UP){
            handler.removeCallbacks(hold);if(dragging){mouseEvent(1);dragging=false;}
            else if(!buttonMode&&!multi&&travel<dp(12)&&SystemClock.uptimeMillis()-began<450){mouseEvent(0);mouseEvent(1);}
        }else if(action==MotionEvent.ACTION_CANCEL)releaseTouches();
        return true;
    }
    private float centroid(MotionEvent e,boolean x){float v=0;for(int i=0;i<e.getPointerCount();i++)v+=x?e.getX(i):e.getY(i);return v/e.getPointerCount();}
    private float span(MotionEvent e){return spanExcept(e,-1);}
    private float spanExcept(MotionEvent e,int skip){
        float x=centroidExcept(e,true,skip),y=centroidExcept(e,false,skip),sum=0;int count=0;
        for(int i=0;i<e.getPointerCount();i++)if(i!=skip){sum+=(float)Math.hypot(e.getX(i)-x,e.getY(i)-y);count++;}
        return count==0?0:2*sum/count;
    }
    private float centroidExcept(MotionEvent e,boolean x,int skip){float v=0;int count=0;for(int i=0;i<e.getPointerCount();i++)if(i!=skip){v+=x?e.getX(i):e.getY(i);count++;}return count==0?0:v/count;}
    public boolean onGenericMotionEvent(MotionEvent e){if(ready()&&e.getAction()==MotionEvent.ACTION_SCROLL){Point p=map(e.getX(),e.getY());session.control.scroll(p.x,p.y,remoteW,remoteH,e.getAxisValue(MotionEvent.AXIS_HSCROLL),e.getAxisValue(MotionEvent.AXIS_VSCROLL));return true;}return super.onGenericMotionEvent(e);}
    protected void onDetachedFromWindow(){releaseTouches();super.onDetachedFromWindow();}
}
