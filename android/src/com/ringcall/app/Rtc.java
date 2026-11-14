package com.ringcall.app;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CandidatePairChangeEvent;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.List;

/** WebRTC 语音通话封装（只做音频）。 */
public class Rtc {
    private static final String TAG = "Rtc";
    private static final String[] ICE_URLS = {
            "stun:your-server.example.com:3478",
            "turn:your-server.example.com:3478?transport=udp",
            "turn:your-server.example.com:3478?transport=tcp",
            "stun:stun.l.google.com:19302"
    };
    // 自建 TURN 的静态账号（与服务器 turnserver.conf 中的 user 一致）
    private static final String TURN_USER = "rcuser";
    private static final String TURN_PASS = "YOUR_TURN_PASS";

    public interface Listener {
        void onRtcSdp(JSONObject sdpJson);

        void onRtcIce(JSONObject iceJson);

        void onRtcState(String state);
    }

    private final Context ctx;
    private final Listener listener;
    private PeerConnectionFactory factory;
    private PeerConnection pc;
    private AudioTrack localAudioTrack;
    private AudioSource audioSource;
    private volatile boolean closed;

    public Rtc(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void create() {
        if (pc != null) return;
        try {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions());

            PeerConnectionFactory.Builder b = PeerConnectionFactory.builder();
            factory = b.createPeerConnectionFactory();

            MediaConstraints ac = new MediaConstraints();
            ac.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
            ac.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
            ac.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
            ac.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
            ac.optional.add(new MediaConstraints.KeyValuePair("googEchoCancellation2", "true"));
            ac.optional.add(new MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"));
            ac.optional.add(new MediaConstraints.KeyValuePair("googAutoGainControl2", "true"));

            audioSource = factory.createAudioSource(ac);
            localAudioTrack = factory.createAudioTrack("ringcall_audio0", audioSource);

            List<PeerConnection.IceServer> ice = new ArrayList<>();
            for (String u : ICE_URLS) {
                PeerConnection.IceServer.Builder ib = PeerConnection.IceServer.builder(u);
                if (u.startsWith("turn:")) {
                    ib.setUsername(TURN_USER).setPassword(TURN_PASS);
                }
                ice.add(ib.createIceServer());
            }

            pc = factory.createPeerConnection(ice, new PeerConnection.Observer() {
                @Override
                public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                }

                @Override
                public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                    Log.i(TAG, "ice: " + state);
                    if (state == PeerConnection.IceConnectionState.CONNECTED
                            || state == PeerConnection.IceConnectionState.COMPLETED) {
                        listener.onRtcState("connected");
                    } else if (state == PeerConnection.IceConnectionState.FAILED) {
                        listener.onRtcState("failed");
                    } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        listener.onRtcState("disconnected");
                    }
                }

                @Override
                public void onIceConnectionReceivingChange(boolean b) {
                }

                @Override
                public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                }

                @Override
                public void onIceCandidate(IceCandidate candidate) {
                    try {
                        JSONObject ice = new JSONObject();
                        ice.put("candidate", candidate.sdp);
                        ice.put("sdpMid", candidate.sdpMid);
                        ice.put("sdpMLineIndex", candidate.sdpMLineIndex);
                        JSONObject payload = new JSONObject();
                        payload.put("ice", ice);
                        listener.onRtcIce(payload);
                    } catch (Exception e) {
                        Log.e(TAG, "ice json", e);
                    }
                }

                @Override
                public void onIceCandidatesRemoved(IceCandidate[] candidates) {
                }

                @Override
                public void onAddStream(MediaStream stream) {
                }

                @Override
                public void onRemoveStream(MediaStream stream) {
                }

                @Override
                public void onDataChannel(DataChannel dataChannel) {
                }

                @Override
                public void onRenegotiationNeeded() {
                }

                @Override
                public void onSelectedCandidatePairChanged(CandidatePairChangeEvent event) {
                }

                @Override
                public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                    Log.i(TAG, "remote audio track added");
                }

                @Override
                public void onTrack(RtpTransceiver transceiver) {
                }
            });

            if (pc != null) pc.addTrack(localAudioTrack);
            Log.i(TAG, "peer connection created");
        } catch (Throwable t) {
            Log.e(TAG, "create rtc failed", t);
        }
    }

    /** 处理网页端发来的 sdp / ice 载荷。 */
    public void handleSignal(JSONObject payload) {
        if (pc == null || payload == null) return;
        try {
            if (payload.has("sdp")) {
                JSONObject sdp = payload.getJSONObject("sdp");
                String type = sdp.optString("type");
                String text = sdp.optString("sdp");
                SessionDescription desc;
                if ("offer".equals(type)) {
                    desc = new SessionDescription(SessionDescription.Type.OFFER, text);
                } else if ("answer".equals(type)) {
                    desc = new SessionDescription(SessionDescription.Type.ANSWER, text);
                } else {
                    return;
                }
                Log.i(TAG, "setRemoteDescription " + type);
                pc.setRemoteDescription(new SimpleSdpObserver(), desc);
                if ("offer".equals(type)) createAnswer();
            } else if (payload.has("ice")) {
                JSONObject c = payload.getJSONObject("ice");
                IceCandidate cand = new IceCandidate(c.optString("sdpMid"),
                        c.optInt("sdpMLineIndex"), c.optString("candidate"));
                pc.addIceCandidate(cand);
                Log.i(TAG, "addIceCandidate");
            }
        } catch (Exception e) {
            Log.e(TAG, "handleSignal", e);
        }
    }

    private void createAnswer() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        pc.createAnswer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                pc.setLocalDescription(new SimpleSdpObserver(), sdp);
                try {
                    JSONObject inner = new JSONObject();
                    inner.put("type", "answer");
                    inner.put("sdp", sdp.description);
                    JSONObject payload = new JSONObject();
                    payload.put("sdp", inner);
                    listener.onRtcSdp(payload);
                } catch (Exception e) {
                    Log.e(TAG, "answer json", e);
                }
            }

            @Override
            public void onSetSuccess() {
            }

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "createAnswer failed: " + s);
            }

            @Override
            public void onSetFailure(String s) {
                Log.e(TAG, "createAnswer set failed: " + s);
            }
        }, constraints);
    }

    public void setSpeaker(boolean on) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(on);
        } catch (Exception e) {
            Log.e(TAG, "speaker", e);
        }
    }

    public void setMicEnabled(boolean on) {
        if (localAudioTrack != null) localAudioTrack.setEnabled(on);
    }

    public void close() {
        closed = true;
        try {
            if (pc != null) {
                pc.close();
                pc = null;
            }
        } catch (Exception ignored) {}
        try {
            if (localAudioTrack != null) {
                localAudioTrack.dispose();
                localAudioTrack = null;
            }
        } catch (Exception ignored) {}
        try {
            if (audioSource != null) {
                audioSource.dispose();
                audioSource = null;
            }
        } catch (Exception ignored) {}
        try {
            if (factory != null) {
                factory.dispose();
                factory = null;
            }
        } catch (Exception ignored) {}
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setSpeakerphoneOn(false);
                am.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Exception ignored) {}
    }

    public boolean isClosed() {
        return closed;
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String s) {
            Log.e(TAG, "onCreateFailure " + s);
        }

        @Override
        public void onSetFailure(String s) {
            Log.e(TAG, "onSetFailure " + s);
        }
    }
}
