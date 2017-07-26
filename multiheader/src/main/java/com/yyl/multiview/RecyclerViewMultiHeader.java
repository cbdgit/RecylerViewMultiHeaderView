package com.yyl.multiview;


import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.support.annotation.CallSuper;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.yyl.multiview.impl.ScreenChangeCallBack;
import com.yyl.multiview.impl.ScreenChangeSmallCallBack;
import com.yyl.multiview.impl.ScreenSmallOnClick;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

/**
 * Created by yyl on 2016/5/31/031.
 * <p>
 * https://github.com/mengzhidaren/RecylerViewMultiHeaderView
 *
 * @author yyl
 */
public class RecyclerViewMultiHeader extends ViewGroup implements ScreenChangeCallBack {
    private String tag = "RecyclerViewMultiHeader";
    @Visibility
    private int intendedVisibility = VISIBLE;
    private int downTranslation;
    private boolean recyclerWantsTouch;
    private boolean isVertical;
    private boolean isAttachedToRecycler;
    private int videoHeight;

    //线程交互有点频繁      在有些奇葩手机上线程更新不同步  用原子操作
    private volatile boolean hidden;
    private volatile boolean isFullVideoState;
    private volatile boolean currentVideoStateMax = true;
    private boolean webViewScrollBarEnabled;
    private boolean stateVideoSmallDisable;
    private RecyclerViewDelegate recyclerView;
    private LayoutManagerDelegate layoutManager;

    private RecyclerView recyclerRoot;
    private WebView webViewRoot;
    //默认视频比例
    private float videoScale = 9f / 16f;
    private float mTouchSlop;

    public static final int STATE_VIDEO = 0;
    public static final int STATE_HEAD = 1;  //标准的headView
    public static final int STATE_WEB = 2;
    private int state = STATE_VIDEO;

    /**
     * 关联头WebView入口
     *
     * @param recycler rootView
     * @param webView  rootWebView
     */
    public final void attachToWebView(RecyclerView recycler, WebView webView) {
        validate(webView);
        this.webViewRoot = webView;
        this.webViewScrollBarEnabled = webView.isVerticalScrollBarEnabled();
        state = STATE_WEB;
        attachToVideo(recycler, null);
    }

    /**
     * 关联头View入口
     *
     * @param recycler rootView
     */
    public final void attachToHeader(RecyclerView recycler) {
        state = STATE_HEAD;
        attachToVideo(recycler, null);
    }

    /**
     * 关联视频入口
     *
     * @param recycler     rootView
     * @param screenChange VideoScreenChange
     */
    public final void attachToVideo(@NonNull final RecyclerView recycler, ScreenChangeCallBack screenChange) {
        validate(recycler);
        this.screenChange = screenChange;
        this.recyclerRoot = recycler;
        this.recyclerView = RecyclerViewDelegate.with(recycler);
        this.layoutManager = LayoutManagerDelegate.with(recycler.getLayoutManager());
        isVertical = layoutManager.isVertical();
        isAttachedToRecycler = true;
        recyclerView.setHeaderDecoration(new HeaderItemDecoration());
        recyclerView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recycler, int dx, int dy) {
                if (!isFullVideoState && recyclerView.hasItems()) {
                    if (layoutManager.isFirstRowVisible()) {
                        int offset = recyclerView.getScrollOffset(isVertical);//当前recyclerview的偏移量
                        hidden = offset > videoHeight;
                    } else {
                        hidden = true;
                    }
                    onScrollChanged();
                }
            }

        });
    }

    /**
     * Detaches <code>RecyclerViewHeader</code> from <code>RecyclerView</code>.
     */
    public final void detach() {
        if (isAttachedToRecycler) {
            isAttachedToRecycler = false;
            recyclerWantsTouch = false;
            recyclerView.reset();
            recyclerView = null;
            layoutManager = null;
        }
    }

    public RecyclerViewMultiHeader(Context context) {
        super(context);
        init(context, null);
    }

    public RecyclerViewMultiHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public RecyclerViewMultiHeader(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        if (attrs != null) {
            final TypedArray a = context.obtainStyledAttributes(
                    attrs, R.styleable.RecyclerViewMultiHeader);
            videoScale = a.getFloat(R.styleable.RecyclerViewMultiHeader_videoScale, videoScale);
            state = a.getInt(R.styleable.RecyclerViewMultiHeader_viewState, STATE_VIDEO);
            a.recycle();
        }
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    private final ArrayList<View> mMatchParentChildren = new ArrayList<>(1);

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        //修改掉自身的高度适应头布局
        int heightParams = getLayoutParams().height;
        switch (state) {
            case STATE_VIDEO:
                onMeasureVideo(widthMeasureSpec, heightMeasureSpec, heightParams);
                return;
            case STATE_WEB:
                if (heightParams != FrameLayout.LayoutParams.MATCH_PARENT) {//WebView中父高度必须MATCH_PARENT
                    getLayoutParams().height = FrameLayout.LayoutParams.MATCH_PARENT;
                }
                break;
            case STATE_HEAD:
                if (heightParams != FrameLayout.LayoutParams.WRAP_CONTENT) {
                    getLayoutParams().height = FrameLayout.LayoutParams.WRAP_CONTENT;
                }
                break;
            default:
                break;
        }
        onMeasureAll(widthMeasureSpec, heightMeasureSpec);
        LogUtils.i(tag, "onMeasure  measureWidth=" + getMeasuredWidth() + "measureHeight" + getMeasuredHeight());
    }

    //指定测量视频的大小
    private void onMeasureVideo(int widthMeasureSpec, int heightMeasureSpec, int heightParams) {
        if (isFullVideoState && heightParams != FrameLayout.LayoutParams.MATCH_PARENT) {
            getLayoutParams().height = FrameLayout.LayoutParams.MATCH_PARENT;
        } else if (!isFullVideoState && heightParams != FrameLayout.LayoutParams.WRAP_CONTENT) {
            getLayoutParams().height = FrameLayout.LayoutParams.WRAP_CONTENT;
        }
        int measureWidth = MeasureSpec.getSize(widthMeasureSpec);
        int measureHeight = MeasureSpec.getSize(heightMeasureSpec);
        measureHeight = isFullVideoState ? measureHeight : (int) (measureWidth * videoScale);
        setMeasuredDimension(measureWidth, measureHeight);
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                final int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(measureWidth, MeasureSpec.EXACTLY);
                final int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(measureHeight, MeasureSpec.EXACTLY);
                child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
            }
        }
    }

    private void onMeasureAll(int widthMeasureSpec, int heightMeasureSpec) {
        int measureWidth = MeasureSpec.getSize(widthMeasureSpec);
        //int measureHeight = MeasureSpec.getSize(heightMeasureSpec);
        if (state == STATE_WEB) {
            if (getLayoutParams().height == FrameLayout.LayoutParams.WRAP_CONTENT) {
                getLayoutParams().height = FrameLayout.LayoutParams.MATCH_PARENT;
            }
        }
        int count = getChildCount();
        final boolean measureMatchParentChildren =
                MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY ||
                        MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY;
        mMatchParentChildren.clear();
        int maxHeight = 0;
        int childState = 0;
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
                final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
                maxHeight = Math.max(maxHeight,
                        child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
                childState = combineMeasuredStates(childState, child.getMeasuredState());
                if (measureMatchParentChildren) {
                    if (lp.width == FrameLayout.LayoutParams.MATCH_PARENT ||
                            lp.height == FrameLayout.LayoutParams.MATCH_PARENT) {
                        mMatchParentChildren.add(child);
                    }
                }
            }
        }
        setMeasuredDimension(measureWidth,
                resolveSizeAndState(maxHeight, heightMeasureSpec,
                        childState << MEASURED_HEIGHT_STATE_SHIFT));
        count = mMatchParentChildren.size();
        if (count > 1) {
            for (int i = 0; i < count; i++) {
                final View child = mMatchParentChildren.get(i);
                final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                final int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(measureWidth, MeasureSpec.EXACTLY);
                final int childHeightMeasureSpec;
                if (lp.height == FrameLayout.LayoutParams.MATCH_PARENT) {
                    childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(
                            getMeasuredHeight(), MeasureSpec.EXACTLY);
                } else {
                    childHeightMeasureSpec = getChildMeasureSpec(heightMeasureSpec, 0, lp.height);
                }
                child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
            }
        }
    }


    private void onScrollChanged() {
        if (state != STATE_VIDEO || stateVideoSmallDisable) {
            super.setVisibility(hidden ? INVISIBLE : intendedVisibility);
            if (!hidden) {
                moveTranslation();
            }
            return;
        }
        if (isFullVideoState) {//全屏无动作
            return;
        }
        if (hidden == currentVideoStateMax) {
            if (screenChangeSmall != null) {
                screenChangeSmall.changeMiniScaleState(hidden);
            }
        }
        changeInVisibleState(hidden);
    }

    private void changeInVisibleState(boolean hidden) {
        if (hidden) {
            changeSmallState();
        } else {
            changePrimordialState();
            moveTranslation();
        }
    }

    private void moveTranslation() {
        final int translation = calculateTranslation();
        if (isVertical) {
            setTranslationY(translation);
        } else {
            setTranslationX(translation);
        }
    }


    private void changeSmallState() {
        if (state != STATE_VIDEO) return;
        currentVideoStateMax = false;
        setScaleX(0.5f);
        setScaleY(0.5f);
        setTranslationX(getWidth() >> 2);
        setTranslationY(-(getHeight() >> 2));
    }


    private void changePrimordialState() {
        if (state != STATE_VIDEO) return;
        currentVideoStateMax = true;
        primordialState();
    }

    private void primordialState() {
        setScaleX(1f);
        setScaleY(1f);
        setTranslationX(0);
        setTranslationY(0);
    }


    /**
     * 移动偏移量
     *
     * @return 偏移量
     */
    private int calculateTranslation() {
        int offset = recyclerView.getScrollOffset(isVertical);
        int base = layoutManager.isReversed() ? recyclerView.getTranslationBase(isVertical) : 0;
        return base - offset;
    }

    @Override
    public final void setVisibility(@Visibility int visibility) {
        this.intendedVisibility = visibility;
        if (!hidden) {
            super.setVisibility(intendedVisibility);
        }
    }


    @Visibility
    @Override
    public final int getVisibility() {
        return intendedVisibility;
    }

    @Override
    protected final void onLayout(boolean changed, int l, int t, int r, int b) {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                // final int width = child.getMeasuredWidth();
                //   final int height = child.getMeasuredHeight();
                child.layout(0, 0, getMeasuredWidth(), getMeasuredHeight());
            }
        }
        if (changed && isAttachedToRecycler) {
            //  LogUtils.i(tag, "onLayout    changed=" + changed);
            int verticalMargins = 0;
            int horizontalMargins = 0;
            if (getLayoutParams() instanceof MarginLayoutParams) {
                final MarginLayoutParams layoutParams = (MarginLayoutParams) getLayoutParams();
                verticalMargins = layoutParams.topMargin + layoutParams.bottomMargin;
                horizontalMargins = layoutParams.leftMargin + layoutParams.rightMargin;
            }
            if (getHeight() + verticalMargins > 0)
                videoHeight = getHeight() + verticalMargins;
            recyclerView.onHeaderSizeChanged(getHeight() + verticalMargins, getWidth() + horizontalMargins);
            LogUtils.i(tag, "onLayout   onScrollChanged");
            onScrollChanged();
        }
    }


    @Override
    @CallSuper
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (state == STATE_WEB) {
            return true;
        }
        if (hidden && !isFullVideoState && state == STATE_VIDEO) {
            //点击小屏播放器
            return true;
        }
        if (isFullVideoState) {
            //全屏不拦截 继续向下传递事件
            return false;
        }
        recyclerWantsTouch = isAttachedToRecycler && recyclerView.onInterceptTouchEvent(event);
        if (recyclerWantsTouch && event.getAction() == MotionEvent.ACTION_MOVE) {
            downTranslation = calculateTranslation();
        }
        //  Log.i(tag, "onInterceptTouchEvent    recyclerWantsTouch=" + recyclerWantsTouch);
        return recyclerWantsTouch || super.onInterceptTouchEvent(event);
    }


    @Override
    @CallSuper
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (state == STATE_WEB) {
            return onTouchWebView(event);
        }
        if (hidden && !isFullVideoState && state == STATE_VIDEO) {
            if (event.getAction() == MotionEvent.ACTION_UP) {//点击小屏播放器
                if (smallOnClick != null)
                    smallOnClick.onClick();
            }
            return true;
        }
        if (isFullVideoState) {
            //全屏不向上传事件 也就是不向recyclerView发送事件在这一层消费掉
            return true;
        }
        if (recyclerWantsTouch) { // this cannot be true if recycler is not attached
            int scrollDiff = downTranslation - calculateTranslation();
            int verticalDiff = isVertical ? scrollDiff : 0;
            int horizontalDiff = isVertical ? 0 : scrollDiff;
            MotionEvent recyclerEvent =
                    MotionEvent.obtain(event.getDownTime(),
                            event.getEventTime(),
                            event.getAction(),
                            event.getX() - horizontalDiff,
                            event.getY() - verticalDiff,
                            event.getMetaState());
            try {
                recyclerView.onTouchEvent(recyclerEvent);
                return false;
            } catch (IllegalArgumentException ex) {
                ex.printStackTrace();
            }
        }
        return super.onTouchEvent(event);
    }

    private void validate(RecyclerView recyclerView) {
        if (recyclerView.getLayoutManager() == null) {
            throw new IllegalStateException("Be sure to attach RecyclerViewHeader after setting your RecyclerView's LayoutManager.");
        }
    }

    private void validate(WebView webView) {
        if (webView == null) {
            throw new IllegalStateException("Be sure to attach RecyclerViewHeader after setting your RecyclerView's LayoutManager.");
        }
    }


    private class HeaderItemDecoration extends RecyclerView.ItemDecoration {
        private int headerHeight;
        private int headerWidth;
        private int firstRowSpan;

        public HeaderItemDecoration() {
            firstRowSpan = layoutManager.getFirstRowSpan();
        }

        public void setWidth(int width) {
            headerWidth = width;
        }

        public void setHeight(int height) {
            headerHeight = height;
        }

        @Override
        public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
            super.getItemOffsets(outRect, view, parent, state);
            final boolean headerRelatedPosition = parent.getChildLayoutPosition(view) < firstRowSpan;
            int heightOffset = headerRelatedPosition && isVertical ? headerHeight : 0;
            int widthOffset = headerRelatedPosition && !isVertical ? headerWidth : 0;
            if (layoutManager.isReversed()) {
                outRect.bottom = heightOffset;
                outRect.right = widthOffset;
            } else {
                outRect.top = heightOffset;
                outRect.left = widthOffset;
            }
        }
    }

    private static class RecyclerViewDelegate {

        @NonNull
        private final RecyclerView recyclerView;
        private HeaderItemDecoration decoration;
        private RecyclerView.OnScrollListener onScrollListener;
        private RecyclerView.OnChildAttachStateChangeListener onChildAttachListener;

        private RecyclerViewDelegate(final @NonNull RecyclerView recyclerView) {
            this.recyclerView = recyclerView;
        }

        public static RecyclerViewDelegate with(@NonNull RecyclerView recyclerView) {
            return new RecyclerViewDelegate(recyclerView);
        }

        public final void onHeaderSizeChanged(int height, int width) {
            if (decoration != null) {
                decoration.setHeight(height);
                decoration.setWidth(width);
                recyclerView.post(new Runnable() {
                    @Override
                    public void run() {
                        invalidateItemDecorations();
                    }
                });
            }
        }

        private void invalidateItemDecorations() {
            if (!recyclerView.isComputingLayout()) {
                recyclerView.invalidateItemDecorations();
            }
        }

        public final int getScrollOffset(boolean isVertical) {
            return isVertical ? recyclerView.computeVerticalScrollOffset() : recyclerView.computeHorizontalScrollOffset();
        }

        public final int getTranslationBase(boolean isVertical) {
            return isVertical ?
                    recyclerView.computeVerticalScrollRange() - recyclerView.getHeight() :
                    recyclerView.computeHorizontalScrollRange() - recyclerView.getWidth();
        }

        public final boolean hasItems() {
            return recyclerView.getAdapter() != null && recyclerView.getAdapter().getItemCount() != 0;
        }

        public final void setHeaderDecoration(HeaderItemDecoration decoration) {
            clearHeaderDecoration();
            this.decoration = decoration;
            recyclerView.addItemDecoration(this.decoration, 0);
        }

        public final void clearHeaderDecoration() {
            if (decoration != null) {
                recyclerView.removeItemDecoration(decoration);
                decoration = null;
            }
        }

        public final void setOnScrollListener(RecyclerView.OnScrollListener onScrollListener) {
            clearOnScrollListener();
            this.onScrollListener = onScrollListener;
            recyclerView.addOnScrollListener(this.onScrollListener);
        }

        public final void clearOnScrollListener() {
            if (onScrollListener != null) {
                recyclerView.removeOnScrollListener(onScrollListener);
                onScrollListener = null;
            }
        }

        public final void setOnChildAttachListener(RecyclerView.OnChildAttachStateChangeListener onChildAttachListener) {
            clearOnChildAttachListener();
            this.onChildAttachListener = onChildAttachListener;
            recyclerView.addOnChildAttachStateChangeListener(this.onChildAttachListener);
        }

        public final void clearOnChildAttachListener() {
            if (onChildAttachListener != null) {
                recyclerView.removeOnChildAttachStateChangeListener(onChildAttachListener);
                onChildAttachListener = null;
            }
        }

        public final void reset() {
            clearHeaderDecoration();
            clearOnScrollListener();
            clearOnChildAttachListener();
        }

        public boolean onInterceptTouchEvent(MotionEvent ev) {
            try {
                return recyclerView.onInterceptTouchEvent(ev);
            } catch (IllegalArgumentException ex) {
                ex.printStackTrace();
            }
            return false;
        }

        public boolean onTouchEvent(MotionEvent ev) {
            return recyclerView.onTouchEvent(ev);
        }

    }

    private static class LayoutManagerDelegate {
        @Nullable
        private final LinearLayoutManager linear;
        @Nullable
        private final GridLayoutManager grid;
        @Nullable
        private final StaggeredGridLayoutManager staggeredGrid;

        private LayoutManagerDelegate(@NonNull RecyclerView.LayoutManager manager) {
            final Class<? extends RecyclerView.LayoutManager> managerClass = manager.getClass();
            if (managerClass == LinearLayoutManager.class) { //not using instanceof on purpose
                linear = (LinearLayoutManager) manager;
                grid = null;
                staggeredGrid = null;
            } else if (managerClass == GridLayoutManager.class) {
                linear = null;
                grid = (GridLayoutManager) manager;
                staggeredGrid = null;
//            } else if (manager instanceof StaggeredGridLayoutManager) { //TODO: 05.04.2016 implement staggered
//                linear = null;
//                grid = null;
//                staggeredGrid = (StaggeredGridLayoutManager) manager;
            } else {
                throw new IllegalArgumentException("Currently RecyclerViewHeader supports only LinearLayoutManager and GridLayoutManager.");
            }
        }

        public static LayoutManagerDelegate with(@NonNull RecyclerView.LayoutManager layoutManager) {
            return new LayoutManagerDelegate(layoutManager);
        }

        public final int getFirstRowSpan() {
            if (linear != null) {
                return 1;
            } else if (grid != null) {
                return grid.getSpanCount();
//            } else if (staggeredGrid != null) {
//                return staggeredGrid.getSpanCount(); //TODO: 05.04.2016 implement staggered
            }
            return 0; //shouldn't get here
        }

        public final boolean isFirstRowVisible() {
            if (linear != null) {
                return linear.findFirstVisibleItemPosition() == 0;
            } else if (grid != null) {
                return grid.findFirstVisibleItemPosition() == 0;
//            } else if (staggeredGrid != null) {
//                return staggeredGrid.findFirstCompletelyVisibleItemPositions() //TODO: 05.04.2016 implement staggered
            }
            return false; //shouldn't get here
        }

        public final boolean isReversed() {
            if (linear != null) {
                return linear.getReverseLayout();
            } else if (grid != null) {
                return grid.getReverseLayout();
//            } else if (staggeredGrid != null) {
//                return ; //TODO: 05.04.2016 implement staggered
            }
            return false; //shouldn't get here
        }

        public final boolean isVertical() {
            if (linear != null) {
                return linear.getOrientation() == LinearLayoutManager.VERTICAL;
            } else if (grid != null) {
                return grid.getOrientation() == LinearLayoutManager.VERTICAL;
//            } else if (staggeredGrid != null) {
//                return ; //TODO: 05.04.2016 implement staggered
            }
            return false; //shouldn't get here
        }
    }

    @IntDef({VISIBLE, INVISIBLE, GONE})
    @Retention(RetentionPolicy.SOURCE)
    private @interface Visibility {
    }

    @Override
    public FrameLayout.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new FrameLayout.LayoutParams(getContext(), attrs);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof FrameLayout.LayoutParams;
    }

    private RecyclerView getRecyclerViewRoot() {
        return recyclerRoot;
    }

    private WebView getWebViewRoot() {
        return webViewRoot;
    }

    private boolean isPointerIndexWeb;
    private boolean isPointerIndexRecycler;
    private boolean webViewCanNotControl;
    private float lastRawY;
    private float fristRawY;
    private boolean canMove;
    private float moveOffset;

    private boolean onTouchWebView(MotionEvent e) {
        if (getWebViewRoot() == null || getRecyclerViewRoot() == null) {
            return super.onTouchEvent(e);//这里不能被调用
        }
        int action = e.getAction();
        switch (action) {
            case MotionEvent.ACTION_CANCEL:
                getRecyclerViewRoot().onTouchEvent(e);
                break;
            case MotionEvent.ACTION_UP:
                getWebViewRoot().onTouchEvent(e);
                if (!isRecyclerViewTop()) {
                    getRecyclerViewRoot().onTouchEvent(e);
                }
                getWebViewRoot().setVerticalScrollBarEnabled(webViewScrollBarEnabled);
                break;
            case MotionEvent.ACTION_DOWN:
                lastRawY = e.getRawY();
                fristRawY = e.getRawY();
                canMove = false;
                isPointerIndexRecycler = true;
                moveOffset = e.getY() - e.getRawY();
                getWebViewRoot().onTouchEvent(e);
                LogUtils.i(tag, "ACTION_DOWN");
                return true;
            case MotionEvent.ACTION_MOVE:
                float newY = e.getRawY();
                float off = fristRawY - newY;
                boolean moveUP = lastRawY - newY > 0;//移动方向
                lastRawY = e.getRawY();
                if (!canMove && Math.abs(off) > mTouchSlop + 0.5f) {//开始移动
                    canMove = true;
                }
                if (!canMove) {
                    getWebViewRoot().onTouchEvent(e);//没有移动时就当发生了  点击事件  发送给WebView
                    return true;
                }
                webViewCanNotControl = !isRecyclerViewTop();
                if (moveUP) {//向上移动
                    if (webViewCanNotControl) {
                        reTryRecyclerViewTouchEvent(e);
                        return true;
                    }
                    if (isWebViewBottom()) { //这里判断在移动webview内容向上时 如果滑到底部切换到recyclerview里去
                        reTryRecyclerViewTouchEvent(e);
                    } else {
                        reTryWebTouchEvent(e);
                    }
                } else {//向下移动
                    //   LogUtils.i(tag, "MOVE Down    webNotControl=" + webNotControl + "  isPointerIndexRecycler=" + isPointerIndexRecycler);
                    if (webViewCanNotControl) {
                        reTryRecyclerViewTouchEvent(e);
                    } else {
                        reTryWebTouchEvent(e);
                    }
                }
                break;
            default:
                break;
        }
        return true;//消费掉所有事件不向RecyclerView传递
    }


    /**
     * 分发新事件to  Web
     *
     * @param e MotionEvent
     */
    private void reTryWebTouchEvent(MotionEvent e) {
        if (isPointerIndexWeb) {
            isPointerIndexWeb = false;
            e.setAction(MotionEvent.ACTION_DOWN);
        }
        getWebViewRoot().onTouchEvent(e);
        //  LogUtils.i(tag, isPointerIndexWeb + " WebTouch    isRecyclerViewTop()=" + isRecyclerViewTop() + "   isWebViewBottom=" + isWebViewBottom());
    }

    /**
     * 分发新事件 to  RecyclerView
     *
     * @param e MotionEvent
     */
    private void reTryRecyclerViewTouchEvent(MotionEvent e) {
        if (isPointerIndexRecycler) {
            isPointerIndexRecycler = false;
            isPointerIndexWeb = true;
            e.setAction(MotionEvent.ACTION_CANCEL);
            getWebViewRoot().onTouchEvent(e);
            e.setAction(MotionEvent.ACTION_DOWN);
            moveOffset = e.getY() - e.getRawY();
            getWebViewRoot().setVerticalScrollBarEnabled(false);
        }
        MotionEvent recyclerEvent =
                MotionEvent.obtain(e.getDownTime(),
                        e.getEventTime(),
                        e.getAction(),
                        0,
                        e.getRawY() + moveOffset,
                        e.getMetaState());
        boolean reslut1 = getRecyclerViewRoot().onTouchEvent(recyclerEvent);
    }


    private boolean isRecyclerViewTop() {
        return !getRecyclerViewRoot().canScrollVertically(-1);
    }

    /**
     * 有些奇葩手机居然滑不到底只能滑到1
     *
     * @return isWebViewBottom WebView是否滑到底
     */
    private boolean isWebViewBottom() {
        WebView w = getWebViewRoot();
        float webcontent = w.getContentHeight() * w.getScale();
        float webnow = w.getHeight() + w.getScrollY();
        return Math.abs(webcontent - webnow) <= 1;
    }

    @Override
    public void onChangeFullScreen(boolean isFullState) {
        this.isFullVideoState = isFullState;
        if (screenChange != null)
            screenChange.onChangeFullScreen(isFullState);
        if (isFullState) {
            recyclerRoot.setLayoutFrozen(true);//禁掉touch事件
            primordialState();
        } else {
            recyclerRoot.setLayoutFrozen(false);
            onScrollChanged();
        }
    }

    public boolean isFullVideoState() {
        return isFullVideoState;
    }


    public void changeSmallState(boolean isShow) {
        setScreenSmallDisable(!isShow);
        setVisibility(VISIBLE);
        notifyChangeScroll();
    }

    public void notifyChangeScroll() {
        onScrollChanged();
    }

    private ScreenChangeCallBack screenChange;
    private ScreenSmallOnClick smallOnClick;
    private ScreenChangeSmallCallBack screenChangeSmall;

    public void setScreenSmallDisable(boolean stateVideoSmallDisable) {
        this.stateVideoSmallDisable = stateVideoSmallDisable;
    }

    public void setScreenSmallOnClick(ScreenSmallOnClick smallOnClick) {
        this.smallOnClick = smallOnClick;
    }

    public void setScreenChangeSmallCallBack(ScreenChangeSmallCallBack screenChangeSmall) {
        this.screenChangeSmall = screenChangeSmall;
    }
}