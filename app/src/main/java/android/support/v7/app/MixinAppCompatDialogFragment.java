/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.v7.app;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import androidx.annotation.RestrictTo;
import androidx.appcompat.app.AppCompatDialog;
import androidx.core.app.MixinDialogFragment;


import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

public class MixinAppCompatDialogFragment extends MixinDialogFragment {

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        return new AppCompatDialog(getContext(), getTheme());
    }

    /** @hide */
    @SuppressLint("RestrictedApi")
    @RestrictTo(LIBRARY_GROUP)
    @Override
    public void setupDialog(Dialog dialog, int style) {
        if (dialog instanceof AppCompatDialog) {
            // If the dialog is an AppCompatDialog, we'll handle it
            AppCompatDialog acd = (AppCompatDialog) dialog;
            switch (style) {
                case STYLE_NO_INPUT:
                    dialog.getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                    // fall through...
                case STYLE_NO_FRAME:
                case STYLE_NO_TITLE:
                    acd.supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
            }
        } else {
            // Else, just let super handle it
            super.setupDialog(dialog, style);
        }
    }

}
