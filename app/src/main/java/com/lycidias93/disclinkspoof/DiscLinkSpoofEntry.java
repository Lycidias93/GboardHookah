package com.lycidias93.disclinkspoof;

import android.hardware.usb.UsbDevice;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class DiscLinkSpoofEntry implements IXposedHookLoadPackage {
    private static final String TAG = "DiscLinkSpoof";
    private static final String TARGET_PACKAGE = "kr.co.hlds.disclink.platinum";

    private static final int REAL_VENDOR_ID = 0x13fd;
    private static final int REAL_PRODUCT_ID = 0x0840;

    private static final int SPOOF_VENDOR_ID = 0x0e8d;
    private static final int SPOOF_PRODUCT_ID = 0x1887;

    private static volatile boolean vendorLogged;
    private static volatile boolean productLogged;
    private static volatile boolean identityReadFailedLogged;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": loaded into " + lpparam.packageName + " process=" + lpparam.processName);

        XposedHelpers.findAndHookMethod(
                UsbDevice.class,
                "getVendorId",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isExactTargetDevice(param.thisObject)) {
                            return;
                        }
                        param.result = SPOOF_VENDOR_ID;
                        if (!vendorLogged) {
                            vendorLogged = true;
                            XposedBridge.log(TAG + ": spoofed vendor 0x13fd -> 0x0e8d");
                        }
                    }
                }
        );

        XposedHelpers.findAndHookMethod(
                UsbDevice.class,
                "getProductId",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isExactTargetDevice(param.thisObject)) {
                            return;
                        }
                        param.result = SPOOF_PRODUCT_ID;
                        if (!productLogged) {
                            productLogged = true;
                            XposedBridge.log(TAG + ": spoofed product 0x0840 -> 0x1887");
                        }
                    }
                }
        );
    }

    private static boolean isExactTargetDevice(Object device) {
        try {
            Object vendorObj = XposedHelpers.getObjectField(device, "mVendorId");
            Object productObj = XposedHelpers.getObjectField(device, "mProductId");
            if (!(vendorObj instanceof Integer) || !(productObj instanceof Integer)) {
                return false;
            }
            int vendorId = (Integer) vendorObj;
            int productId = (Integer) productObj;
            return vendorId == REAL_VENDOR_ID && productId == REAL_PRODUCT_ID;
        } catch (Throwable t) {
            if (!identityReadFailedLogged) {
                identityReadFailedLogged = true;
                XposedBridge.log(TAG + ": exact UsbDevice field identity read failed: " + t);
            }
            return false;
        }
    }
}
