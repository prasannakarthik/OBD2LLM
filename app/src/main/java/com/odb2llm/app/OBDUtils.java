package com.odb2llm.app;

import android.annotation.SuppressLint;
import android.util.Log;

public class OBDUtils {

    public static final String TAG = "OBD2llm";

    public static String decodeOBDResponse(String response) {
        response = response.replaceAll("[^0-9A-Fa-f]", "");

        if (response.length() % 2 != 0) {
            return "Invalid response";
        }

        byte[] dataBytes = hexStringToByteArray(response);
        if (dataBytes.length < 2) {
            return "no pid";
        }
        int mode = dataBytes[0]; // Mode is the first byte
        int pid = dataBytes[1];  // PID is the second byte

        return parseOBDResponse(mode, pid, dataBytes);
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    public static String parseOBDResponse(int mode, int pid, byte[] dataBytes) {
        if (mode != 0x41) {
            String errorMessage = "Only Mode 01 (Show current data) is supported.";
            Log.d(OBDUtils.TAG, errorMessage);
            return "no message";
        }

        switch (pid) {
            case 0x01: // Monitor Status (since DTCs cleared)
                // Byte 2 represents the MIL status and the number of stored DTCs
                boolean milOn = (dataBytes[2] & 0x80) != 0; // Check if the MIL (bit 7) is ON
                int numDTCs = dataBytes[2] & 0x7F;          // Extract bits 6–0 for number of DTCs

                // Byte 3 indicates the availability of tests
                int testAvailability = dataBytes[3];        // Bit mask for available tests

                // Byte 4 shows the completion status of the tests
                int testCompletionStatus = dataBytes[4];    // Bit mask for completed tests

                // Format the output
                String milStatusStr = milOn ? "ON" : "OFF";
                @SuppressLint("DefaultLocale") String monitorStatusStr = String.format(
                        "MIL status: %s, Stored DTCs: %d, Test availability: 0x%02X, Test completion: 0x%02X",
                        milStatusStr, numDTCs, testAvailability, testCompletionStatus);

                // Log the result
                Log.d(OBDUtils.TAG, monitorStatusStr);
                return monitorStatusStr;

            case 0x03: // Diagnostic Trouble Codes (DTCs)
                StringBuilder dtcBuilder = new StringBuilder();
                dtcBuilder.append("Stored DTCs: ");

                // Each DTC is represented by two bytes (5 characters after decoding)
                for (int i = 2; i < dataBytes.length; i += 2) {
                    if (dataBytes[i] == 0 && dataBytes[i + 1] == 0) {
                        // No more DTCs (0x0000 indicates no further trouble codes)
                        break;
                    }

                    // Decode the DTC
                    char category = decodeDtcCategory(dataBytes[i]); // Get category (P, C, B, or U)
                    int code = ((dataBytes[i] & 0x0F) << 8) | dataBytes[i + 1]; // Combine nibbles
                    @SuppressLint("DefaultLocale") String dtc = String.format("%c%04d", category, code);

                    // Append to the result
                    dtcBuilder.append(dtc).append(", ");
                }

                // Remove trailing comma and space, if any
                if (dtcBuilder.length() > 13) {
                    dtcBuilder.setLength(dtcBuilder.length() - 2);
                } else {
                    dtcBuilder.append("None");
                }

                String dtcResult = dtcBuilder.toString();
                Log.d(OBDUtils.TAG, dtcResult);
                return dtcResult;

            case 0x04: // Calculated Engine Load
                double engineLoad = (dataBytes[2] / 2.0);
                @SuppressLint("DefaultLocale") String engineLoadStr = String.format("Engine load is %.1f%%.", engineLoad);
                Log.d(OBDUtils.TAG, engineLoadStr);
                return engineLoadStr;

            case 0x05: // Engine Coolant Temperature
                int coolantTemp = dataBytes[2] - 40;
                @SuppressLint("DefaultLocale") String coolantTempStr = String.format("Engine coolant temperature is %d°C.", coolantTemp);
                Log.d(OBDUtils.TAG, coolantTempStr);
                return coolantTempStr;

            case 0x06: // Short Term Fuel Trim (Bank 1)
                double shortTermFuelTrim = ((dataBytes[2] - 128) * 100.0) / 128;
                @SuppressLint("DefaultLocale") String shortTermFuelTrimStr = String.format("Short term fuel trim (Bank 1) is %.1f%%.", shortTermFuelTrim);
                Log.d(OBDUtils.TAG, shortTermFuelTrimStr);
                return shortTermFuelTrimStr;

            case 0x07: // Long Term Fuel Trim (Bank 1)
                double longTermFuelTrim = ((dataBytes[2] - 128) * 100.0) / 128;
                @SuppressLint("DefaultLocale") String longTermFuelTrimStr = String.format("Long term fuel trim (Bank 1) is %.1f%%.", longTermFuelTrim);
                Log.d(OBDUtils.TAG, longTermFuelTrimStr);
                return longTermFuelTrimStr;

            case 0x0A: // Fuel Pressure
                int fuelPressure = dataBytes[2] * 3;
                @SuppressLint("DefaultLocale") String fuelPressureStr = String.format("Fuel pressure is %d kPa.", fuelPressure);
                Log.d(OBDUtils.TAG, fuelPressureStr);
                return fuelPressureStr;

            case 0x0B: // Intake Manifold Absolute Pressure
                int manifoldPressure = dataBytes[2];
                @SuppressLint("DefaultLocale") String manifoldPressureStr = String.format("Intake manifold absolute pressure is %d kPa.", manifoldPressure);
                Log.d(OBDUtils.TAG, manifoldPressureStr);
                return manifoldPressureStr;

            case 0x0C: // Engine RPM
                int byte3 = (dataBytes.length > 3) ? dataBytes[3] : 0;
                int rpm = ((dataBytes[2] * 256) + byte3) / 4;
                @SuppressLint("DefaultLocale") String rpmStr = String.format("Engine RPM is %d ", rpm);
                Log.d(OBDUtils.TAG, rpmStr);
                return rpmStr;

            case 0x0D: // Vehicle Speed
                int vehicleSpeed = dataBytes[2];
                @SuppressLint("DefaultLocale") String vehicleSpeedStr = String.format("Vehicle speed is %d km/h.", vehicleSpeed);
                Log.d(OBDUtils.TAG, vehicleSpeedStr);
                return vehicleSpeedStr;

            case 0x0E: // Timing Advance
                double timingAdvance = (dataBytes[2] / 2.0) - 64;
                @SuppressLint("DefaultLocale") String timingAdvanceStr = String.format("Timing advance is %.1f°.", timingAdvance);
                Log.d(OBDUtils.TAG, timingAdvanceStr);
                return timingAdvanceStr;

            default:
                String defaultMessage = "PID not recognized or not supported.";
                Log.d(OBDUtils.TAG, defaultMessage);
                return "no match";
        }
    }
    private static char decodeDtcCategory(int byteValue) {
        int categoryCode = (byteValue & 0xC0) >> 6; // Extract the first 2 bits
        switch (categoryCode) {
            case 0:
                return 'P'; // Powertrain
            case 1:
                return 'C'; // Chassis
            case 2:
                return 'B'; // Body
            case 3:
                return 'U'; // Network
            default:
                return '?'; // Unknown (shouldn't happen)
        }
    }

}
