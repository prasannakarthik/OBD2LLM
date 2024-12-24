package com.odb2llm.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;

import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.fragment.app.Fragment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import com.odb2llm.app.TextEmbeddingsViewModel;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    private String deviceAddress;
    private SerialService service;

    public  TextView receiveText;
    private TextView sendText;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    private TextEmbeddingsViewModel textEmbeddingsViewModel;

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        status("Loading model in a few seconds..\n");

        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);

        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        return view;
    }

   @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.backgroundNotification).setChecked(service != null && service.areNotificationsEnabled());
        } else {
            menu.findItem(R.id.backgroundNotification).setChecked(true);
            menu.findItem(R.id.backgroundNotification).setEnabled(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else if (id == R.id.backgroundNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!service.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
                } else {
                    showNotificationSettings();
                }
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);

            status("Connecting to OBD2 Module " + deviceAddress);
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private final Semaphore semaphore = new Semaphore(1);
    private void generateResponseAsync(String prompt) {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        InferenceModel inferenceModel = InferenceModel.Companion.getInstance(getActivity());
        executorService.submit(() -> {
            try {
                if (prompt != null) {
                    // Perform the async inference
                    semaphore.acquire();
                    inferenceModel.generateResponseAsync(prompt, new InferenceModel.InferenceCallback() {
                        @Override
                        public void onPartialResult(String partialResult, boolean isDone) {
                            if (partialResult != null) {
                                    Log.d("LLMINFERENCE", "Partial Result: " + partialResult + "\n" + "isdone " + isDone);
                                    if (getActivity() != null) {
                                        getActivity().runOnUiThread(() -> receiveText.append(partialResult));
                                    }
                            } else {
                                Log.d("LLMINFERENCE", "Received null partial result.");
                            }
                        }

                        @Override
                        public void onFinalResult(String finalResult) {
                            if (finalResult != null) {
                                Log.d("LLMINFERENCE", "finalResult : " + finalResult);
                                if (getActivity() != null) {
                                    getActivity().runOnUiThread(() -> receiveText.append(finalResult));
                                }

                                semaphore.release();
                            } else {
                                Log.d("LLMINFERENCE", "Received null partial result.");
                            }
                        }
                    });
                }
                else {
                    Log.d("LLMINFERENCE", "Prompt is null, cannot perform inference.");
                }
            } catch (Exception e) {
                Log.e("LLMINFERENCE", "Error during inference: ", e);
            }
        });
    }

    private String OBD2inference(String prompt) {
        String updatedPrompt = prompt;

        try {
            InferenceModel inferenceModel = InferenceModel.Companion.getInstance(getActivity());
            updatedPrompt = inferenceModel.generateResponse(prompt);  // Get the generated response.
            Log.d("LLMInference", "Generated response: " + updatedPrompt);
            if (getActivity() != null) {
                //String finalUpdatedPrompt = updatedPrompt;
                SpannableStringBuilder spannablePrompt = new SpannableStringBuilder(updatedPrompt + '\n'); // Combine updatedPrompt and newline
                int promptLength = prompt.length(); // Save prompt length for span application

                // Ensure prompt length doesn't exceed the spannable text length
                if (promptLength > spannablePrompt.length()) {
                    Log.e("SpanError", "Prompt length exceeds spannable text length. Adjusting.");
                    promptLength = spannablePrompt.length();
                }

                // Apply span with validated range
                spannablePrompt.setSpan(
                        new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)),
                        0,
                        promptLength,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );

                // Update the UI with the spannable text
                getActivity().runOnUiThread(() -> receiveText.append(spannablePrompt));
            }
        } catch (IllegalArgumentException e) {
        } catch (Exception e) {
            Log.e("LLMInference", "Unexpected error occurred: " + e.getMessage(), e);
        }
        return updatedPrompt;
    }

    private void send(String str) {
        sendText.setText("");
        SpannableStringBuilder prompt = new SpannableStringBuilder(str + '\n');
        prompt.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, prompt.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(prompt+"\n");

        textEmbeddingsViewModel = new ViewModelProvider(this).get(TextEmbeddingsViewModel.class);
        textEmbeddingsViewModel.setUpMLModel(getActivity().getApplicationContext());

        String decodedobd2code = textEmbeddingsViewModel.calculateSimilarity(str);

        /* give a creative answer */
        if ("No match found".equals(decodedobd2code) || decodedobd2code == null) {
            //generateResponseAsync(advice_prompt + str);
            //generateResponseAsync("reply to this in 10 words stricly: " + str);
            OBD2inference("<start_of_turn>user Respond in not more than 10 words only" + str + "<end_of_turn>model>");
            Log.d("OBD2LLM", "--str len is --" + str.length());
            /*if (str != null && str.length() != 6) {
                SpannableStringBuilder rprompt = new SpannableStringBuilder(str + '\n');
                rprompt.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorRecieveText)), 0, rprompt.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                receiveText.append(rprompt);
            }*/
        } else {    /* send obd2 code across */
            str = decodedobd2code.substring(0, 4); // Take the first character
            Log.d("OBD2LLM", "sending odb2code:" + str);

            if (connected != Connected.True) {
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                String msg;
                byte[] data;
                if (hexEnabled) {
                    StringBuilder sb = new StringBuilder();
                    TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                    TextUtil.toHexString(sb, newline.getBytes());
                    msg = sb.toString();
                    data = TextUtil.fromHexString(msg);
                } else {
                    msg = str;
                    data = (str + newline).getBytes();
                }
                SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
                spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                service.write(data);
            } catch (Exception e) {
                onSerialIoError(e);
            }
        }
    }

    /********************************************************/
    public static String decodeOBDResponse(String response) {
        // Remove spaces from the response string before processing
        response = response.replaceAll("[^0-9A-Fa-f]", "");

        // Ensure the response has an even length (if not, return error)
        if (response.length() % 2 != 0) {
            return "Invalid response length.";
        }

        // Convert the hex string response into an array of bytes using ByteBuffer
        //byte[] dataBytes = hexStringToByteArray(response);
        byte[] dataBytes = hexStringToByteArray(response);

        // Log the byte array to check its contents
        Log.d("OBD2llm", "Decoded dataBytes: " + byteArrayToHex(dataBytes));

        // Check if we have at least 2 bytes for mode and PID
        if (dataBytes.length < 2) {
            return "no pid";
        }

        // Parse the Mode and PID from the response (assuming Mode 01 and PID 0x04)
        int mode = dataBytes[0]; // Mode is the first byte
        int pid = dataBytes[1];  // PID is the second byte

        // Call the appropriate method for parsing based on Mode and PID
        String tmp = parseOBDResponse(mode, pid, dataBytes);
        Log.d("obd2llm", "tmp: " + tmp);
        return tmp;

    }

    // Helper method to convert hex string to byte array
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    // Helper method to convert byte array to hex string
    public static String byteArrayToHex(byte[] byteArray) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : byteArray) {
            hexString.append(String.format("%02X", b)); // Format each byte as a 2-character hex string
            hexString.append(" "); // Add space between each byte
        }
        return hexString.toString().trim(); // Trim the last space
    }

    // Helper method to decode the category of the DTC

    /************************/

    public static String parseOBDResponse(int mode, int pid, byte[] dataBytes) {
        if (mode != 0x41) {
            String errorMessage = "Only Mode 01 (Show current data) is supported.";
            Log.d("OBD2llm", errorMessage);
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
                String monitorStatusStr = String.format(
                        "MIL status: %s, Stored DTCs: %d, Test availability: 0x%02X, Test completion: 0x%02X",
                        milStatusStr, numDTCs, testAvailability, testCompletionStatus);

                // Log the result
                Log.d("OBD2llm", monitorStatusStr);
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
                    String dtc = String.format("%c%04d", category, code);

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
                Log.d("OBD2llm", dtcResult);
                return dtcResult;

            case 0x04: // Calculated Engine Load
                double engineLoad = (dataBytes[2] / 2.0);
                String engineLoadStr = String.format("Engine load is %.1f%%.", engineLoad);
                Log.d("OBD2llm", engineLoadStr);
                return engineLoadStr;

            case 0x05: // Engine Coolant Temperature
                int coolantTemp = dataBytes[2] - 40;
                String coolantTempStr = String.format("Engine coolant temperature is %d°C.", coolantTemp);
                Log.d("OBD2llm", coolantTempStr);
                return coolantTempStr;

            case 0x06: // Short Term Fuel Trim (Bank 1)
                double shortTermFuelTrim = ((dataBytes[2] - 128) * 100.0) / 128;
                String shortTermFuelTrimStr = String.format("Short term fuel trim (Bank 1) is %.1f%%.", shortTermFuelTrim);
                Log.d("OBD2llm", shortTermFuelTrimStr);
                return shortTermFuelTrimStr;

            case 0x07: // Long Term Fuel Trim (Bank 1)
                double longTermFuelTrim = ((dataBytes[2] - 128) * 100.0) / 128;
                String longTermFuelTrimStr = String.format("Long term fuel trim (Bank 1) is %.1f%%.", longTermFuelTrim);
                Log.d("OBD2llm", longTermFuelTrimStr);
                return longTermFuelTrimStr;

            case 0x0A: // Fuel Pressure
                int fuelPressure = dataBytes[2] * 3;
                String fuelPressureStr = String.format("Fuel pressure is %d kPa.", fuelPressure);
                Log.d("OBD2llm", fuelPressureStr);
                return fuelPressureStr;

            case 0x0B: // Intake Manifold Absolute Pressure
                int manifoldPressure = dataBytes[2];
                String manifoldPressureStr = String.format("Intake manifold absolute pressure is %d kPa.", manifoldPressure);
                Log.d("OBD2llm", manifoldPressureStr);
                return manifoldPressureStr;

            case 0x0C: // Engine RPM
                int byte3 = (dataBytes.length > 3) ? dataBytes[3] : 0;
                int rpm = ((dataBytes[2] * 256) + byte3) / 4;
                String rpmStr = String.format("Engine speed or rpm is %d ", rpm);
                Log.d("OBD2llm", rpmStr);
                return rpmStr;

            case 0x0D: // Vehicle Speed
                int vehicleSpeed = dataBytes[2];
                String vehicleSpeedStr = String.format("Vehicle speed is %d km/h.", vehicleSpeed);
                Log.d("OBD2llm", vehicleSpeedStr);
                return vehicleSpeedStr;

            case 0x0E: // Timing Advance
                double timingAdvance = (dataBytes[2] / 2.0) - 64;
                String timingAdvanceStr = String.format("Timing advance is %.1f°.", timingAdvance);
                Log.d("OBD2llm", timingAdvanceStr);
                return timingAdvanceStr;

            default:
                String defaultMessage = "PID not recognized or not supported.";
                Log.d("OBD2llm", defaultMessage);
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
    /************************/
    private void receive(ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {
            if (hexEnabled) {
                spn.append(TextUtil.toHexString(data)).append('\n');
            } else {
                String msg = new String(data);
                if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                    // don't show CR as ^M if directly before LF
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                    // special handling if CR and LF come in separate fragments
                    if (pendingNewline && msg.charAt(0) == '\n') {
                        if(spn.length() >= 2) {
                            spn.delete(spn.length() - 2, spn.length());
                        } else {
                            Editable edt = receiveText.getEditableText();
                            if (edt != null && edt.length() >= 2)
                                edt.delete(edt.length() - 2, edt.length());
                        }
                    }
                    pendingNewline = msg.charAt(msg.length() - 1) == '\r';
                }
                spn.append(TextUtil.toCaretString(msg, newline.length() != 0));
            }
        }
        if(String.valueOf(spn).contains("NO DATA")) {
            SpannableStringBuilder prompt = new SpannableStringBuilder("No data from OBD\n");
            prompt.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, prompt.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            getActivity().runOnUiThread(() -> receiveText.append(prompt));
        }

        String comment_on = decodeOBDResponse(String.valueOf(spn));

        Log.d("ODB2llm", "msg from OBD2" + spn + "meaning: " + comment_on);
        if (comment_on.split("\\s+").length > 3 && getActivity() != null) {
          //   getActivity().runOnUiThread(() -> receiveText.append("\n" + comment_on + "\n\n"));
            //generateResponseAsync("<start_of_turn>user As an automotive mechanic, provide only a 5-word comment on" + comment_on + "nothing else. " +
 //                  "Do not include 'Sure,' 'Here is,' or any additional text. Respond with exactly 5 words <end_of_turn>model");
            OBD2inference("<start_of_turn>user As an automotive mechanic, provide only a 5-word comment on" + comment_on + "nothing else. " +
                "Do not include 'Sure,' 'Here is,' or any additional text. Respond with exactly 5 words <end_of_turn>");
        }

    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */

    private void showNotificationSettings() {
        Intent intent = new Intent();
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
        intent.putExtra("android.provider.extra.APP_PACKAGE", getActivity().getPackageName());
        startActivity(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(Arrays.equals(permissions, new String[]{Manifest.permission.POST_NOTIFICATIONS}) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !service.areNotificationsEnabled())
            showNotificationSettings();
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
        //OBD2inference("<start_of_turn>user You're auto mechanic and diagnostics technical. Introduce yourslef in 4 words<end_of_turn>model");
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }
    public void updateReceiveText(String text) {
        if (receiveText != null) {
            receiveText.append(text + "\n");
        } else {
            Log.e("TerminalFragment", "receiveText is null!");
        }
    }
    public interface Callback {
           void onResponse(String response);
    }
}
