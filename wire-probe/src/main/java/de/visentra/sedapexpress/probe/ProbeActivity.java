package de.visentra.sedapexpress.probe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Calls the wire code, so that R8 keeps it and the release APK shows its real size. */
public class ProbeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final TextView view = new TextView(this);
        view.setText(ProbeEntry.run(this));
        setContentView(view);
    }
}
