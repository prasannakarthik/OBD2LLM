package de.kai_morich.simple_bluetooth_terminal;

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
import androidx.fragment.app.Fragment;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
            status("connecting...");
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

    private String decodeOBD2Prompt = "You are an OBD-II code translator. Your sole task is to interpret user queries about vehicle diagnostics and return the corresponding OBD-II hexadecimal request code. " +
            "Your response must be strictly limited to the two-byte hexadecimal code without any explanation or additional text. Use the following mappings as your reference:\n" +
            "Monitor Status: 0101\n" +
            "DTC Causing Freeze Frame: 0102\n" +
            "Fuel System Status: 0103\n" +
            "Engine Load: 0104\n" +
            "Coolant Temperature: 0105\n" +
            "Short Term Fuel Trim—Bank 1: 0106\n" +
            "Long Term Fuel Trim—Bank 1: 0107\n" +
            "Short Term Fuel Trim—Bank 2: 0108\n" +
            "Long Term Fuel Trim—Bank 2: 0109\n" +
            "Fuel Pressure: 010A\n" +
            "Intake Manifold Pressure: 010B\n" +
            "Engine RPM: 010c\n" +
            "Vehicle Speed: 010D\n" +
            "Timing Advance: 010E\n" +
            "Intake Air Temperature: 010F\n" +
            "Mass Air Flow Rate: 0110\n" +
            "Throttle Position: 0111\n" +
            "Commanded Secondary Air Status: 0112\n" +
            "Oxygen Sensors Present: 0113\n" +
            "Oxygen Sensor 1: 0114\n" +
            "Oxygen Sensor 2: 0115\n" +
            "Run Time Since Engine Start: 011F";

    private String decodeOBD2ResponsePrompt =
            "You are a highly skilled automotive diagnostic assistant. Your task is to interpret and decode OBD-II (On-Board Diagnostics) responses sent from an ELM327" +
            "adapter. The responses are raw hexadecimal data and correspond to specific diagnostic commands. "
            + "Instructions: Decode the hexadecimal response based on the OBD-II PID (Parameter Identifier) sent. "
            + "    Provide a clear explanation of the decoded data. "
            + "    Use standard OBD-II decoding rules to calculate the result, e.g., converting speed, RPM, or sensor values into human-readable formats. "
            + "    If the response is invalid or malformed, explain why and suggest potential issues. "
            + " Example Input and Output: "
            + "Input: "
            + " Command: 010D "
            + "Response: 41 0D 32 "
            + "Output: "
            + "     Decoded Command: Vehicle Speed Sensor (PID 0x0D). "
            + "    Decoded Value: The speed is 50 km/h. "
            + "        Explanation: The hexadecimal value 32 is 50 in decimal. According to OBD-II, PID 0x0D reports speed in km/h. "
            + " New Input for Decoding: "
            + " Command: 010D "
            + "Response: 141 0D 00 "
            + "Your Turn: "
            + "Decode the response. Provide the decoded value, explanation, and any additional insights. ";


    private void generateResponseAsync(String prompt, Callback callback) {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        InferenceModel inferenceModel = InferenceModel.Companion.getInstance(getActivity());
        executorService.submit(() -> {
            try {
                // Perform the async inference
                inferenceModel.generateResponseAsync(prompt, new InferenceModel.InferenceCallback() {
                    @Override
                    public void onPartialResult(String partialResult, boolean isDone) {
                        Log.d("LLMINFERENCE", "Partial Result: " + partialResult);
                        if (callback != null) {
                            callback.onResponse(partialResult);
                        }
                    }
                    @Override
                    public void onFinalResult(String finalResult) {
                        Log.d("LLMINFERENCE", "Final Result: " + finalResult);
                        if (callback != null) {
                            callback.onResponse(finalResult); // Call onResponse when final result is available
                        }
                    }
                });
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
        } catch (IllegalArgumentException e) {
        } catch (Exception e) {
            Log.e("LLMInference", "Unexpected error occurred: " + e.getMessage(), e);
        }
        return updatedPrompt;
    }

    //        str = OBD2inference(decodeOBD2Prompt + str);
    private void send(String str) {
       str = OBD2inference(decodeOBD2Prompt + str);
        if (str != null && str.length() > 0) {
            str = str.substring(0, 4); // Take the first character
        }

        Log.d("llminference", "str is" + str);

        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            if(hexEnabled) {
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
            receiveText.append(spn);
            service.write(data);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

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
        receiveText.append(spn);
        Log.d("LLMInferece", "spn is " + spn);
        String str = "";
        str = OBD2inference(decodeOBD2ResponsePrompt + spn);
        Log.d("LLMInferece", "str is" + str);
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
