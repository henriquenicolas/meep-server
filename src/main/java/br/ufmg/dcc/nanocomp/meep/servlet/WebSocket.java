package br.ufmg.dcc.nanocomp.meep.servlet;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import ncsa.hdf.object.ScalarDS;
import ncsa.hdf.object.h5.H5File;

@ServerEndpoint(value="/websocket")
public class WebSocket {

	private static final AtomicInteger THREAD_ID = new AtomicInteger(0);
	private static final Pattern EZ_PATTERN = Pattern.compile("ez-\\d{6}.\\d{2}.h5");
	public static final int[] DK_BLUE_RED = new int[]{-16252876,-16187337,-16121798,-16056259,-15990720,-15859644,-15794105,-15728566,-15663026,-15597487,-15531947,-15531944,-15466405,-15400866,-15269791,-15204252,-15204248,-15138708,-15138705,-15073166,-15073163,-15073160,-15073157,-15007617,-15007613,-15007610,-14942071,-15007604,-15073136,-15073133,-15073130,-15073127,-15138659,-15138656,-15138653,-15138649,-15204182,-15204179,-15269712,-15269709,-15269705,-15269701,-15335234,-15335231,-15400764,-15466297,-15531829,-15597362,-15597358,-15662891,-15793960,-15859493,-15925025,-16056094,-16121627,-16252696,-16318228,-16449297,-16514830,-16580362,-16514312,-16382215,-16250119,-16118022,-15986181,-15854084,-15721988,-15589891,-15392002,-15128577,-14865409,-14601985,-14272769,-14009345,-13745921,-13548033,-13284609,-13021185,-12757761,-12428801,-12165633,-11902209,-11638785,-11375617,-11111937,-10914049,-10716673,-10453505,-10190337,-9927169,-9663745,-9334785,-9071617,-8808449,-8545281,-8347649,-8084737,-7887105,-7623937,-7360769,-7097601,-6768897,-6505985,-6243073,-5979905,-5716993,-5454081,-5191169,-4928257,-4665345,-4402689,-4074241,-3811073,-3548161,-3285249,-3022337,-2759425,-2496769,-2299393,-2102017,-1839105,-1576449,-1313793,-1051137,-854017,-591361,-394241,-197122,-66052,-1031,-1546,-2062,-2577,-3093,-3609,-4125,-4897,-5668,-6439,-6955,-7727,-8499,-9271,-10043,-11071,-11844,-12360,-13132,-13904,-14676,-15448,-16220,-17248,-18020,-18792,-19821,-20849,-21877,-22905,-23932,-24704,-25731,-26759,-27787,-28815,-30100,-31384,-32412,-33440,-34468,-35239,-36522,-38062,-39090,-40374,-41658,-42686,-43971,-45255,-46539,-47823,-49106,-50390,-51674,-53215,-54499,-55527,-122347,-189166,-255728,-256754,-323316,-389622,-456184,-457210,-523772,-655357,-917500,-1114107,-1310713,-1572856,-1769462,-1966069,-2162675,-2424818,-2621425,-2818031,-3014638,-3276782,-3473389,-3735532,-3932139,-4128746,-4325354,-4521961,-4784105,-5046249,-5242857,-5439464,-5636072,-5832679,-6094823,-6291431,-6488039,-6750182,-6946790,-7143398,-7340006,-7602149,-7798756,-7995365,-8191973,-8454117,-8716262,-8912870,-9109478,-9306086,-9502695,-9699303,-9961448,-10223592,-10420201,-10616811,-10813420,-11010029,-11206637,-11468782,-11665391,-11862000,-12058609,-12255218,-12451828,-12648437,-12845046,-13041655,-13172728};
	private static final ByteBuffer[] PALLET;
	private static final int PALLET_TOP;

	private File dir;
	private Session session;

	static {
		try(InputStream is = WebSocket.class.getResourceAsStream("/pallet.png")) {
			BufferedImage image = ImageIO.read(is);
			PALLET = new ByteBuffer[image.getWidth()];
			PALLET_TOP = PALLET.length-1;
			for(int i = 0; i<image.getWidth(); i++) {
				int rgb = image.getRGB(i, 0);
				PALLET[i] = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(rgb);
				PALLET[i].rewind();
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private synchronized void sendText(String text) throws IOException {
		session.getBasicRemote().sendText(text);
	}

	@OnOpen
	public void onOpen(Session session) {
		try {
			dir = File.createTempFile("meepserver", "");
			dir.delete();
			this.session = session;
			if(!dir.mkdir()) throw new IOException("Failed to create file");
			sendText("info:Sending data to server...");
		} catch (IOException e) {
			try {
				sendText("error:Failed to create user directory");
				sendText("end");
			} catch (Exception e1) {
				// ignore
			}
		}
	}

	@OnClose
	public void onClose() {
		for(File f : dir.listFiles()) {
			f.delete();
		}
		dir.delete();
	}

	@OnMessage
	public void onMessage(InputStream is) {
		try (InputStreamReader reader = new InputStreamReader(is,StandardCharsets.UTF_16LE)){
			Process process = Runtime.getRuntime().exec("meep", null, dir);

			Thread errorWatcher = new Thread(()->{
				try(Scanner scanner = new Scanner(process.getErrorStream())) {
					while(session.isOpen()&&scanner.hasNextLine()) {
						if(session.isOpen()) {
							sendText("error:"+scanner.nextLine());
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			},"meep-error-watcher-"+THREAD_ID.incrementAndGet());

			Thread outputWatcher = new Thread(()->{
				try(Scanner scanner = new Scanner(process.getInputStream())){
					while(session.isOpen() && scanner.hasNextLine()) {
						if(session.isOpen()) {
							sendText("info:"+scanner.nextLine());
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			},"meep-output-watcher-"+THREAD_ID.incrementAndGet());

			Thread timeoutWatcher = new Thread(()->{
				try {
					outputWatcher.join(15*60*1000); //Waits tops 15 min
					errorWatcher.join(60*1000);
					if(session.isOpen()) {
						process.waitFor(1, TimeUnit.MINUTES);
					}
					if(process.isAlive()) {
						if(session.isOpen()) {
							sendText("error:Timeout, meep running for more than 15 minutes");
							sendText("end");
						}
					} else {
						if(session.isOpen()) {
							sendText("info:Simulation finished");
							sendText("end:simulation");
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					process.destroyForcibly();
				}
			},"meep-timeout-watcher-"+THREAD_ID.incrementAndGet());

			outputWatcher.start();
			errorWatcher.start();
			timeoutWatcher.start();

			try(OutputStream os = process.getOutputStream();
					Writer writer = new OutputStreamWriter(os)){
				char[] buffer = new char[4096];
				int size;
				while((size = reader.read(buffer))!=-1) {
					writer.write(buffer,0,size);
				}
			}
		} catch(Exception e) {
			try {
				if(session.isOpen()) {
					sendText("error:Failed to execute meep");
					sendText("end");
				}
			} catch (IOException e1) {
				//ignore
			}
		}
	}

	@OnMessage
	public void onMessage(String s) {
		new Thread(()->{
			try {
				double range = 0;
				File[] files = dir.listFiles();
				ArrayList<double[]> scalars = new ArrayList<>(files.length);
				int h=0,w=0;
				for(File file: files) {
					if(EZ_PATTERN.matcher(file.getName()).matches()) {
						H5File h5File = new H5File(file.getAbsolutePath());
						ScalarDS scalar =  (ScalarDS) h5File.get("ez");
						double[] data =  (double[]) scalar.getData();
						h = scalar.getHeight();
						w = scalar.getWidth();
						h5File.close();
						scalars.add(data);
						for(int k = 0;k<data.length;k++) {
							double v = Math.abs(data[k]);
							if(v>range) range = data[k];
						}
					}
				}
				double factor = PALLET_TOP/(range*2);
				for(double[] data : scalars) {
					if(!session.isOpen()) break;
					int i,j, k = w-1;
					for(j = 0; j<k;j++){
						for(i = 0; i < h;i++){
							int index = (int) ((data[i*w+j]+range)*factor);
							index = index<0?0:index>PALLET_TOP?PALLET_TOP:index;
							session.getBasicRemote().sendBinary(PALLET[index],false);
						}
					}
					k = h-1;
					for(i = 0; i < k;i++){
						int index = (int) ((data[i*w+j]+range)*factor);
						index = index<0?0:index>PALLET_TOP?PALLET_TOP:index;
						session.getBasicRemote().sendBinary(PALLET[index],false);
					}
					int index = (int) ((data[i*w+j]+range)*factor);
					index = index<0?0:index>PALLET_TOP?PALLET_TOP:index;
					session.getBasicRemote().sendBinary(PALLET[index],true);
				}
				if(session.isOpen()) {
					session.getBasicRemote().sendText("info:Image loading complete");
					session.getBasicRemote().sendText("end");
				}
			} catch (Exception e) {
				e.printStackTrace();
				try {
					if(session.isOpen()) {
						session.getBasicRemote().sendText("error:Failed reading data");
						session.getBasicRemote().sendText("end");
					}
				} catch (IOException e1) {
					e1.printStackTrace();
				}
			}
		},"meep-server-send-image-"+THREAD_ID.incrementAndGet()).start();
	}

}
