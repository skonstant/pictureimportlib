# pictureimportlib
Android picture import library

Drop in library to import pictures from documents, gallery and camera. No permission needed on Marshmallow+. Cropping supported.

Just include the library in your build.gradle:

```groovy
compile 'com.vol:pictureimportlib:1.1'
```

Change the files provider authority in strings:

```xml
<string name="filesAuthority">your authority identifier</string>
```

Start the activity with parameters, or without:

```java
Intent intent = new Intent(activity, ImportPictureActivity.class);
intent.putExtra(ImportPictureActivity.ARG_WIDTH, 480);
intent.putExtra(ImportPictureActivity.ARG_HEIGHT, 640);
intent.putExtra(ImportPictureActivity.ARG_CROP, true);
startActivityForResult(intent, RC_IMAGE_IMPORT);
```

And get the resulting file in onActivityResult()

```java
@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
	if (requestCode == RC_IMAGE_IMPORT && resultCode == RESULT_OK) {
	 	File file = (File) data.getSerializableExtra(ImportPictureActivity.RES_IMAGE_FILE);
	 	//DO something with the file
 	}
}
```
