package io.github.thorvolume.control;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** 展示版本、项目地址、许可证和隐私说明。 */
public final class AboutActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        ((TextView) findViewById(R.id.version)).setText(getString(
                R.string.about_version, BuildConfig.VERSION_NAME, getString(R.string.edition_name)));
        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        findViewById(R.id.open_github).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { AppLinks.openProject(AboutActivity.this); }
        });
    }
}
