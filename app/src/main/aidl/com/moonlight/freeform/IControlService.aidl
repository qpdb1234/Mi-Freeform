// IControlService.aidl
package com.moonlight.freeform;

// Declare any non-default types here with import statements
import com.moonlight.freeform.bean.MotionEventBean;
import com.moonlight.freeform.callback.IOnRotationChangedListener;
import com.moonlight.freeform.callback.IAppRunningListener;
import android.view.MotionEvent;

interface IControlService {
    boolean init();
    void pressBack(int displayId);
    void touch(in MotionEventBean motionEventBean);
    boolean moveStack(int displayId);
    boolean execShell(String command, boolean useRoot);
    // execShell only returns the exit code; when a window is force-hidden we must report "which app requested it", which needs the command's text output.
    String execShellText(String command, boolean useRoot);
}