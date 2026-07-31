/*
 * Copyright 2018 Google LLC
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

package se.lth.math.videoimucapture;


import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

/**
 * Helper class for handling dangerous permissions for Android API level >= 23 which
 * requires user consent at runtime to access the camera.
 *
 * CAMERA is the only hard requirement. Location (GNSS track) and activity recognition
 * (step counter) are requested in the same prompt but recording works without them —
 * the corresponding streams are simply absent from the output.
 */
class PermissionHelper {
    public static final int RC_PERMISSION_REQUEST = 9222;

    public static boolean hasCameraPermission(Activity activity) {
        return ContextCompat.checkSelfPermission(activity,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean mustShowRationale(Activity activity) {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA);
    }

    public static void requestCameraPermission(Activity activity) {
        ArrayList<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 29) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }
        ActivityCompat.requestPermissions(activity,
                permissions.toArray(new String[0]), RC_PERMISSION_REQUEST);
    }

    /**
     * Launch Application Setting to grant permission.
     */
    public static void launchPermissionSettings(Activity activity) {
        Intent intent = new Intent();
        intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
        activity.startActivity(intent);
    }

}
