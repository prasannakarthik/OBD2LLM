package com.odb2llm.app;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.LifecycleObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements LifecycleObserver, FragmentManager.OnBackStackChangedListener {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //generateResponseAsync("Compose an email to remind Brett of lunch plans at noon on Saturday");

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        else
            onBackStackChanged();
    }

    @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount() > 0);
    }
/*
    private void generateResponseAsync(String prompt) {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        InferenceModel inferenceModel = InferenceModel.Companion.getInstance(this);
        executorService.submit(() -> {
            try {
                inferenceModel.generateResponseAsync(prompt, new InferenceModel.InferenceCallback() {
                    @Override
                    public void onPartialResult(String partialResult, boolean isDone) {
                        Log.d("LLMINFERENCE", "Partial Result: " + partialResult + ", Done: " + isDone);
                        TextView responseTextView = findViewById(R.id.receive_text); // Replace with your TextView ID
                        if (responseTextView != null) {
                            responseTextView.append(partialResult);
                        }
                        if (isDone) {
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "Response generation completed.", Toast.LENGTH_SHORT).show();
                            });
                        }
                    }
                    @Override
                    public void onFinalResult(String finalResult) {
                        // Log and update UI with the final result
                        Log.d("LLMINFERENCE", "Final Result: " + finalResult);
                    }
                });
            } catch (Exception e) {
                Log.e("LLMINFERENCE", "Error during inference: ", e);
            }
        });
    }
*/
    /*private void generateResponse(String prompt) {
        try {
            InferenceModel inferenceModel = InferenceModel.Companion.getInstance(this);
            String response = inferenceModel.generateResponse(prompt);
            Log.d("LLMInference", "Generated response: " + response);
        } catch (IllegalArgumentException e) {
            // Log and handle the error (e.g., model file not found)
        } catch (Exception e) {
            // Log and handle any other exceptions
            Log.e("LLMInference", "Unexpected error occurred: " + e.getMessage(), e);
        }
    }*/


   /* private void sendFileToLLM() {
        String filePath = "/data/local/tmp/llm/knowledge.txt"; // Path to the file
        File file = new File(filePath);
        if (file.exists()) {
            try {
                // Read the file content synchronously
                FileInputStream fileInputStream = new FileInputStream(file);
                byte[] byteArray = new byte[(int) file.length()];
                fileInputStream.read(byteArray);
                String content = new String(byteArray, StandardCharsets.UTF_8); // Convert bytes to string

                // Split the content into chunks and process each chunk
                List<String> chunks = splitContentIntoChunks(content, 1024);
                for (int i = 0; i < chunks.size(); i++) {
                    String chunk = chunks.get(i);

                    // Append the confirmation line to each chunk
                    String modifiedChunk = chunk + "\nIs everything understood? Please say 'OK, ready to go'.";

                    InferenceModel inferenceModel = InferenceModel.Companion.getInstance(this);
                    String response = inferenceModel.generateResponse(modifiedChunk);

                    // Log the response using Log.d() for debugging
                    Log.d(TAG, "LLM Response for chunk " + (i + 1) + ": " + response);
                }

            } catch (IOException e) {
                Log.e(TAG, "Error reading file", e); // Log the error if file reading fails
            }
        } else {
            Toast.makeText(this, "File not found at " + filePath, Toast.LENGTH_SHORT).show();
            Log.e(TAG, "File not found at " + filePath); // Log if the file is not found
        }
    }*/
    /*
    private List<String> splitContentIntoChunks(String content, int maxTokens) {
        List<String> chunks = new ArrayList<>();
        int length = content.length();

        // Split the content into chunks of the specified maxTokens size
        for (int i = 0; i < length; i += maxTokens) {
            int end = Math.min(i + maxTokens, length);
            chunks.add(content.substring(i, end));
        }

        return chunks;
    }
*/

}
