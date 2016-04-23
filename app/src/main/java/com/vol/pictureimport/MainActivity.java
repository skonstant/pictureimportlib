package com.vol.pictureimport;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ImageView;

import com.squareup.picasso.Picasso;
import com.vol.pictureimportlib.ImportPictureActivity;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private static final int RC_IMAGE_IMPORT = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, ImportPictureActivity.class);
                intent.putExtra(ImportPictureActivity.ARG_WIDTH, 480);
                intent.putExtra(ImportPictureActivity.ARG_HEIGHT, 640);
                intent.putExtra(ImportPictureActivity.ARG_CROP, true);

                startActivityForResult(intent, RC_IMAGE_IMPORT);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == RC_IMAGE_IMPORT && resultCode == RESULT_OK) {
            File file = (File) data.getSerializableExtra(ImportPictureActivity.RES_IMAGE_FILE);
            Picasso.with(this).load(file).fit().centerCrop().into((ImageView) findViewById(R.id.imageView));
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
}
