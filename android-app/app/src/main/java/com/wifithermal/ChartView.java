package com.wifithermal;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChartView extends View {

    public static class DataPoint {
        public float elapsed;
        public float temp0;
        public float temp1;
        public float totalMbps;
        public DataPoint(float e, float t0, float t1, float m) {
            elapsed = e; temp0 = t0; temp1 = t1; totalMbps = m;
        }
    }

    private final List<DataPoint> points = new ArrayList<>();
    private final Paint paintTemp0 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintTemp1 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintRate  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintAxis  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGrid  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintText  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float durationSec = 1800;

    public ChartView(Context c) { super(c); init(); }
    public ChartView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setBackgroundColor(Color.parseColor("#0d0d1a"));
        paintTemp0.setColor(Color.parseColor("#ef5350"));
        paintTemp0.setStrokeWidth(3f);
        paintTemp0.setStyle(Paint.Style.STROKE);
        paintTemp0.setStrokeJoin(Paint.Join.ROUND);

        paintTemp1.setColor(Color.parseColor("#ff8a65"));
        paintTemp1.setStrokeWidth(2f);
        paintTemp1.setStyle(Paint.Style.STROKE);
        paintTemp1.setStrokeJoin(Paint.Join.ROUND);
        paintTemp1.setPathEffect(new DashPathEffect(new float[]{8, 6}, 0));

        paintRate.setColor(Color.parseColor("#4fc3f7"));
        paintRate.setStrokeWidth(3f);
        paintRate.setStyle(Paint.Style.STROKE);
        paintRate.setStrokeJoin(Paint.Join.ROUND);

        paintAxis.setColor(Color.parseColor("#888888"));
        paintAxis.setStrokeWidth(1.5f);

        paintGrid.setColor(Color.parseColor("#333355"));
        paintGrid.setStrokeWidth(1f);

        paintText.setColor(Color.parseColor("#aaaaaa"));
        paintText.setTextSize(24f);
    }

    public void setDuration(float sec) { durationSec = sec; }

    public synchronized void addPoint(DataPoint p) {
        points.add(p);
        postInvalidate();
    }

    public synchronized void clear() {
        points.clear();
        postInvalidate();
    }

    /** Update rate for the most recent point, or add new point */
    public synchronized void updateLastRate(float elapsed, float mbps) {
        if (!points.isEmpty()) {
            DataPoint last = points.get(points.size() - 1);
            if (Math.abs(last.elapsed - elapsed) < 1.5f) {
                last.totalMbps = mbps;
                postInvalidate();
                return;
            }
        }
        float t0 = points.isEmpty() ? 0 : points.get(points.size() - 1).temp0;
        float t1 = points.isEmpty() ? 0 : points.get(points.size() - 1).temp1;
        points.add(new DataPoint(elapsed, t0, t1, mbps));
        postInvalidate();
    }

    /** Update temp for most recent point (called when STAT arrives) */
    public synchronized void updateLastTemp(float elapsed, float t0, float t1) {
        if (!points.isEmpty()) {
            DataPoint last = points.get(points.size() - 1);
            last.temp0 = t0;
            last.temp1 = t1;
        } else {
            points.add(new DataPoint(elapsed, t0, t1, 0));
        }
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float W = getWidth(), H = getHeight();
        float padL = 90f, padR = 90f, padT = 20f, padB = 40f;
        float cW = W - padL - padR;
        float cH = H - padT - padB;

        List<DataPoint> snap;
        synchronized (this) { snap = new ArrayList<>(points); }

        float maxTemp = 120f, minTemp = 30f, maxRate = 100f;
        for (DataPoint p : snap) {
            if (p.temp0 > maxTemp) maxTemp = p.temp0;
            if (p.temp1 > maxTemp) maxTemp = p.temp1;
            if (p.temp0 < minTemp) minTemp = p.temp0;
            if (p.totalMbps > maxRate) maxRate = p.totalMbps;
        }
        maxTemp = (float)(Math.ceil(maxTemp / 10.0) * 10 + 10);
        minTemp = (float)(Math.floor(minTemp / 10.0) * 10 - 5);
        maxRate = (float)(Math.ceil(maxRate / 200.0) * 200 + 100);

        for (int i = 0; i <= 5; i++) {
            float y = padT + cH * i / 5f;
            canvas.drawLine(padL, y, padL + cW, y, paintGrid);
            float t = maxTemp - (maxTemp - minTemp) * i / 5f;
            paintText.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(String.format(Locale.US, "%.0f°", t), 4, y + 8, paintText);
            float r = maxRate * (1f - (float) i / 5f);
            paintText.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.format(Locale.US, "%.0f", r), W - 4, y + 8, paintText);
        }

        int totalMin = (int)(durationSec / 60);
        int step = totalMin <= 10 ? 1 : totalMin <= 30 ? 5 : 10;
        paintText.setTextAlign(Paint.Align.CENTER);
        for (int m = 0; m <= totalMin; m += step) {
            float x = padL + cW * m / totalMin;
            canvas.drawLine(x, padT, x, padT + cH, paintGrid);
            canvas.drawText(m + "m", x, H - 4, paintText);
        }

        canvas.drawLine(padL, padT, padL, padT + cH, paintAxis);
        canvas.drawLine(padL, padT + cH, padL + cW, padT + cH, paintAxis);
        canvas.drawLine(padL + cW, padT, padL + cW, padT + cH, paintAxis);

        if (snap.size() < 2) return;

        Path pathT0 = new Path(), pathT1 = new Path(), pathR = new Path();
        boolean fT0 = true, fT1 = true, fR = true;

        for (DataPoint p : snap) {
            float x  = padL + cW * (p.elapsed / durationSec);
            float yT0 = padT + cH * (1f - (p.temp0 - minTemp) / (maxTemp - minTemp));
            float yT1 = padT + cH * (1f - (p.temp1 - minTemp) / (maxTemp - minTemp));
            float yR  = padT + cH * (1f - p.totalMbps / maxRate);
            if (fT0) { pathT0.moveTo(x, yT0); fT0 = false; } else pathT0.lineTo(x, yT0);
            if (fT1) { pathT1.moveTo(x, yT1); fT1 = false; } else pathT1.lineTo(x, yT1);
            if (fR)  { pathR.moveTo(x, yR);   fR  = false; } else pathR.lineTo(x, yR);
        }

        canvas.drawPath(pathT0, paintTemp0);
        canvas.drawPath(pathT1, paintTemp1);
        canvas.drawPath(pathR, paintRate);

        // Legend
        paintText.setTextSize(22f);
        paintText.setTextAlign(Paint.Align.LEFT);
        canvas.drawLine(padL + 10, padT + 15, padL + 40, padT + 15, paintTemp0);
        canvas.drawText("phy0", padL + 46, padT + 21, paintText);
        canvas.drawLine(padL + 110, padT + 15, padL + 140, padT + 15, paintTemp1);
        canvas.drawText("phy1", padL + 146, padT + 21, paintText);
        canvas.drawLine(padL + 210, padT + 15, padL + 240, padT + 15, paintRate);
        canvas.drawText("Mbps", padL + 246, padT + 21, paintText);
        paintText.setTextSize(24f);
    }
}
