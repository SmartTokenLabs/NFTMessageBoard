package tapi.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.utils.Numeric;

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import static java.lang.Thread.sleep;
import static java.nio.charset.StandardCharsets.UTF_8;

@Controller
@RequestMapping("/")
public class APIController
{
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private final Map<String, ScriptData> pkMap = new ConcurrentHashMap<>();

    private final String INFURA_KEY;
    private final String INFURA_IPFS_KEY;
    private final String PRIVATE_KEY;

    private static class ScriptData
    {
        String privateKey;
        String contractAddress;
        String scriptFile;

        public ScriptData(String pk, String ca, String sf)
        {
            privateKey = pk;
            contractAddress = ca;
            scriptFile = sf;
        }
    }

    @Autowired
    public APIController()
    {
        String keys = load("../keys.secret");
        String[] sep = keys.split(",");
        INFURA_KEY = sep[0];
        INFURA_IPFS_KEY = sep[1];
        PRIVATE_KEY = sep[2];

        //connect to the scriptproxy
        //TODO: Put on a thread
        connectToScriptProxy();
    }

    private void connectToScriptProxy()
    {
        Credentials credentials = Credentials.create(PRIVATE_KEY);
        String keyAddress = credentials.getAddress();

        byte[] buffer = new byte[512];
        //establish connection to server
        try (Socket socket = new Socket("scriptproxy.smarttokenlabs.com", 8003))

        //try (Socket socket = new Socket("192.168.43.182", 8003))
        {
            //establish connection
            InputStream input = socket.getInputStream();

            int state = 0;

            while (true)
            {
                if (state == 0)
                {
                    OutputStream output = socket.getOutputStream();
                    //send login
                    byte[] login = formLogin(keyAddress);
                    output.write(login);
                    state = 1;
                }

                int count = input.available();
                if (count > 0)
                {
                    int len = input.read(buffer);

                    String rcvStr = Numeric.toHexString(buffer);
                    System.out.println("Receive Command: " + rcvStr.substring(0, 4));
                    byte type = buffer[0];

                    switch (type)
                    {
                        case 0x02: //maintain comms
                            OutputStream output = socket.getOutputStream();
                            byte[] response = signChallenge(credentials, buffer, len);
                            output.write(response);
                            break;

                        case 0x04: //receive API
                            APIReturn received = scanAPI(buffer, len);
                            System.out.println("YOLESS: " + received.apiName);
                            handleAPIReturn(received);
                            //ec recover
                            //check message is from
                            //add message, find token name
                            //check balance & add to message board
                            //recode this client as separate class with callback
                            break;
                    }
                }

                sleep(10);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void handleAPIReturn(APIReturn received)
    {
        switch (received.apiName)
        {
            case "checkWebMsg":
                //pull token address
                String tokenAddress = received.params.get("addr");
                String message = received.params.get("msg");
                String tokenIdStr = received.params.get("tokenid");
                String chainStr = received.params.get("chain");
                String sig = received.params.get("sig");

                //recover wallet address
                String walletAddr = recoverAddress(message, sig);

                // pull token balance
                // do they own this token?
                boolean isOwned = isOwned(walletAddr, tokenAddress, tokenIdStr, chainStr);

                // find token details
                // populate fields

                System.out.println("YOLESS: " + tokenAddress);
                System.out.println("YOLESS: " + walletAddr);
                break;
            default:
                break;
        }
    }

    private boolean isOwned(String walletAddr, String tokenAddress, String tokenIdStr, String chainStr)
    {
        long chainId = Long.parseLong(chainStr);
        Web3j web3j = EthereumNode.createWeb3jNode(chainId, INFURA_KEY);


        return true;
    }


    private static Function ownerOf(BigInteger token)
    {
        return new Function(
                "ownerOf",
                Collections.singletonList(new Uint256(token)),
                Collections.singletonList(new TypeReference<Address>()
                {
                }));
    }

    private static String recoverAddress(String message, String signatureStr)
    {
        String recoveredAddr = "";

        try
        {
            byte[] msgHash = getEthereumMessageHash(message.getBytes(UTF_8));// : Hash.sha3(msg);
            byte[] signature = Numeric.hexStringToByteArray(signatureStr);

            Sign.SignatureData sigData = sigFromByteArray(signature);
            BigInteger recoveredKey  = Sign.signedMessageHashToKey(msgHash, sigData);
            //recoveredKeyHex = Numeric.toHexStringWithPrefixZeroPadded(recoveredKey, 128);// toHexStringWithPrefix(recoveredKey);
            recoveredAddr = "0x" + Keys.getAddress(recoveredKey);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return recoveredAddr;
    }

    private static Sign.SignatureData sigFromByteArray(byte[] sig)
    {
        if (sig.length < 64 || sig.length > 65) return null;

        byte   subv = sig[64];
        if (subv < 27) subv += 27;

        byte[] subrRev = Arrays.copyOfRange(sig, 0, 32);
        byte[] subsRev = Arrays.copyOfRange(sig, 32, 64);
        return new Sign.SignatureData(subv, subrRev, subsRev);
    }

    static final String MESSAGE_PREFIX = "\u0019Ethereum Signed Message:\n";

    static byte[] getEthereumMessagePrefix(int messageLength) {
        return MESSAGE_PREFIX.concat(String.valueOf(messageLength)).getBytes();
    }

    static byte[] getEthereumMessageHash(byte[] message) {
        byte[] prefix = getEthereumMessagePrefix(message.length);

        byte[] result = new byte[prefix.length + message.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(message, 0, result, prefix.length, message.length);

        return Hash.sha3(result);
    }

    private APIReturn scanAPI(byte[] buffer, int length)
    {
        APIReturn apiReturn = new APIReturn();

        ArgRead arg = getArg(1, buffer);
        apiReturn.apiName = arg.arg;
        int index = 1 + arg.length;

        while (index < length)
        {
            arg = getArg(index, buffer);
            index += arg.length;
            String key = arg.arg;
            arg = getArg(index, buffer);
            index += arg.length;
            String param = arg.arg;
            apiReturn.params.put(key, param);
        }

        return apiReturn;
    }

    private static class ArgRead
    {
        String arg;
        int length;
    }

    private ArgRead getArg(int index, byte[] buffer)
    {
        ArgRead arg = new ArgRead();
        ByteArrayInputStream bais = new ByteArrayInputStream(buffer);
        try (DataInputStream di = new DataInputStream(bais))
        {
            di.skipBytes(index);
            arg.length = di.readByte() & 0xFF;
            byte[] readBuffer = new byte[arg.length];
            di.read(readBuffer);
            //convert to string
            arg.arg = new String(readBuffer, StandardCharsets.UTF_8);
            arg.length += 1;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return arg;
    }

    private byte[] signChallenge(Credentials credentials, byte[] buffer, int length)
    {
        String keyAddress = credentials.getAddress();
        byte[] challenge = new byte[length - 1];
        System.arraycopy(buffer, 1, challenge, 0, length - 1);

        String challengeStr = Numeric.toHexString(challenge);
        System.out.println("Challenge: " + challengeStr);

        //sign this
        Sign.SignatureData signatureData = Sign.signMessage(
                challenge, credentials.getEcKeyPair());

        byte[] sigBytes = bytesFromSignature(signatureData);

        System.out.println("SigBytes: " + Numeric.toHexString(sigBytes));

        //build response

        byte[] response = new byte[1 + 20 + sigBytes.length];

        response[0] = 0x07;

        System.arraycopy(Numeric.hexStringToByteArray(keyAddress), 0, response, 1, 20);
        System.arraycopy(sigBytes, 0, response, 21, sigBytes.length);

        return response;
    }

    private static byte[] bytesFromSignature(Sign.SignatureData signature)
    {
        byte[] sigBytes = new byte[65];
        Arrays.fill(sigBytes, (byte) 0);

        try
        {
            System.arraycopy(signature.getR(), 0, sigBytes, 0, 32);
            System.arraycopy(signature.getS(), 0, sigBytes, 32, 32);
            System.arraycopy(signature.getV(), 0, sigBytes, 64, 1);
        }
        catch (IndexOutOfBoundsException e)
        {
            e.printStackTrace();
        }

        return sigBytes;
    }

    private byte[] formLogin(String keyAddress)
    {
        byte[] key = Numeric.hexStringToByteArray(keyAddress);
        byte[] buffer = new byte[key.length +1];
        buffer[0] = 0x01;
        System.arraycopy(key, 0, buffer, 1, key.length);
        return buffer;
    }

    /***********************************
     * Display message board
     ***********************************/

    @GetMapping(value = "/")
    public String connect(@RequestHeader("User-Agent") String agent,
                          Model model)
    {

        //TODO: inject current messages

        return "message_board";
    }

    /***********************************
     * fetch JSON with current messages
     *
     **********************************/

    @RequestMapping(value = "getMessages", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity getMessages(HttpServletRequest request) throws InterruptedException, ExecutionException {


        String messages = "{\"name\":\"Some JSON\"}";

        return new ResponseEntity<>(messages, HttpStatus.CREATED);
    }

    private long parseChainId(String chainIdStr)
    {
        if (chainIdStr.startsWith("0x"))
        {
            return Numeric.toBigInt(chainIdStr).longValue();
        }
        else
        {
            return Long.parseLong(chainIdStr);
        }
    }


    /***********************************
     * File handling
     ***********************************/

    private String load(String fileName) {
        String rtn = "";
        try {
            char[] array = new char[2048];
            FileReader r = new FileReader(fileName);
            r.read(array);

            rtn = new String(array);
            r.close();

        } catch (IOException e)
        {
            e.printStackTrace();
        }

        return rtn;
    }

    private String loadFile(String fileName) {
        byte[] buffer = new byte[0];
        try {
            InputStream in = getClass()
                    .getClassLoader().getResourceAsStream(fileName);
            buffer = new byte[in.available()];
            int len = in.read(buffer);
            if (len < 1) {
                throw new IOException("Nothing is read.");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return new String(buffer);
    }
}