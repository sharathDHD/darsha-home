package com.sharath.tvscreensaver;

import android.service.dreams.DreamService;

public class SaverDream extends DreamService {

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        setContentView(new ClockView(this));
    }
}
