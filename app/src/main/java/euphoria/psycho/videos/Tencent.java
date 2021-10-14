package euphoria.psycho.videos;

import android.content.Intent;

import java.util.Arrays;
import java.util.regex.Pattern;

import euphoria.psycho.explorer.MainActivity;
import euphoria.psycho.explorer.Native;
import euphoria.psycho.explorer.SettingsFragment;
import euphoria.psycho.player.TencentActivity;
import euphoria.psycho.share.PreferenceShare;

public class Tencent extends BaseExtractor<String[]> {
    private static final Pattern MATCH_QQ = Pattern.compile("qq\\.com");

    protected Tencent(String inputUri, MainActivity mainActivity) {
        super(inputUri, mainActivity);
    }

    public static boolean handle(String uri, MainActivity mainActivity) {
        if (MATCH_QQ.matcher(uri).find()) {
            new Tencent(uri, mainActivity).parsingVideo();
            return true;
        }
        return false;
    }


    @Override
    protected String[] fetchVideoUri(String uri) {
        return Native.fetchTencent(uri,
                PreferenceShare.getPreferences().getString(SettingsFragment.KEY_TENCENT, null));
    }


    @Override
    protected void processVideo(String[] videoUris) {
        Intent intent = new Intent(mMainActivity, TencentActivity.class);
        if (videoUris.length == 1)
            intent.putExtra(TencentActivity.EXTRA_PLAYLSIT, videoUris);
        else {
            intent.putExtra(TencentActivity.EXTRA_PLAYLSIT,
                    Arrays.copyOf(videoUris, videoUris.length - 2));
            intent.putExtra(TencentActivity.EXTRA_VIDEO_ID, videoUris[videoUris.length - 2]);
            intent.putExtra(TencentActivity.EXTRA_VIDEO_FORMAT, Integer.parseInt(videoUris[videoUris.length - 1]));
        }
        mMainActivity.startActivity(intent);
    }
}
