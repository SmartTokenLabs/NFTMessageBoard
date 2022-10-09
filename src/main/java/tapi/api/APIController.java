package tapi.api;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.utils.Numeric;

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;

import static java.lang.Thread.sleep;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction;

@Controller
@RequestMapping("/")
public class APIController
{
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private final Map<String, TokenInfo> tokenData = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<MessageData> messageData = new ConcurrentLinkedDeque<>();

    private final String INFURA_KEY;
    private final String INFURA_IPFS_KEY;
    private final String PRIVATE_KEY;

    @Autowired
    public APIController()
    {
        String keys = load("../keys.secret");
        String[] sep = keys.split(",");
        INFURA_KEY = sep[0];
        INFURA_IPFS_KEY = sep[1];
        PRIVATE_KEY = sep[2];

        //connect to the scriptproxy
        Single.fromCallable(() -> {
            connectToScriptProxy();
            return false;
        }).observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .subscribe(f -> System.out.println("Finish"), Throwable::printStackTrace)
                .isDisposed();
    }



    private void connectToScriptProxy()
    {
        Credentials credentials = Credentials.create(PRIVATE_KEY);
        String keyAddress = credentials.getAddress();

        byte[] buffer = new byte[512];
        //establish connection to server
        try (Socket socket = new Socket("scriptproxy.smarttokenlabs.com", 8003))
        {
            //establish connection
            InputStream input = socket.getInputStream();

            int state = 0;

            long currentTime = System.currentTimeMillis();

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
                            output.flush();
                            break;

                        case 0x04: //receive API
                            APIReturn received = scanAPI(buffer, len);
                            System.out.println("YOLESS: " + received.apiName);
                            responseToAPI(socket, handleAPIReturn(received));
                            break;
                    }
                }

                sleep(100);

                if (System.currentTimeMillis() > currentTime + 90*1000)
                {
                    currentTime = System.currentTimeMillis();
                    state = 0;
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void responseToAPI(Socket socket, boolean pass) throws IOException
    {
        OutputStream output = socket.getOutputStream();
        byte[] writeBytes = new byte[5];
        writeBytes[0] = 0x08;
        System.arraycopy(pass ? "pass".getBytes(UTF_8) : "fail".getBytes(UTF_8), 0, writeBytes, 1, 4);
        output.write(writeBytes);
        output.flush();
    }

    private boolean handleAPIReturn(APIReturn received)
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

                long chainId = parseStringValue(chainStr);
                long tokenId = parseStringValue(tokenIdStr);

                //recover wallet address
                String walletAddr = recoverAddress(message, sig);

                Web3j web3j = EthereumNode.createWeb3jNode(chainId, INFURA_KEY);

                // pull token balance
                // do they own this token?
                boolean isOwned = isOwned(web3j, walletAddr, tokenAddress, tokenId);

                if (!isOwned) return false; //early return if not owned (note we use ECRecover to validate ownership)

                // find token details
                // get name and symbol of token
                TokenInfo thisToken = getTokenInfo(web3j, tokenAddress, chainId, walletAddr);

                //String tAddr, long cId, long tId, String msg
                MessageData msg = new MessageData(tokenAddress, chainId, tokenId, message);
                messageData.add(msg);

                break;
            default:
                break;
        }

        return true;
    }

    private TokenInfo getTokenInfo(Web3j web3j, String tokenAddress, long chainId, String walletAddr)
    {
        String tokenKey = tokenAddress + "-" + chainId;
        TokenInfo tokenInfo = tokenData.get(tokenKey);

        if (tokenInfo == null)
        {
            //get name and symbol and cache to tokenData
            String name = callSmartContractFunction(web3j, stringParam("name"), tokenAddress, walletAddr);
            String symbol = callSmartContractFunction(web3j, stringParam("symbol"), tokenAddress, walletAddr);
            tokenInfo = new TokenInfo(name, symbol);
            tokenData.put(tokenKey, tokenInfo);
        }

        return tokenInfo;
    }

    private boolean isOwned(Web3j web3j, String walletAddr, String tokenAddress, long tokenId)
    {
        String owner = callSmartContractFunction(web3j, ownerOf(BigInteger.valueOf(tokenId)), tokenAddress, walletAddr);
        return owner != null && owner.equalsIgnoreCase(walletAddr);
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

    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedMethods("HEAD", "GET", "PUT", "POST", "DELETE", "PATCH");
    }

    @CrossOrigin(origins = "*")
    @GetMapping(value = "/")
    public String connect(@RequestHeader("User-Agent") String agent,
                          Model model)
    {
        //inject JSON


        return "client_view_front_fallback";
    }

    /***********************************
     * fetch JSON with current messages
     *
     **********************************/

    @RequestMapping(value = "getMessages", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity getMessages(HttpServletRequest request) throws InterruptedException, ExecutionException {

        //output a JSON
        JSONArray jsonArray = new JSONArray();
        for (MessageData msgData : messageData)
        {
            String tKey = msgData.tokenAddr + "-" + msgData.chainId;
            JSONObject obj = new JSONObject();
            TokenInfo tInfo = tokenData.get(tKey);
            if (tInfo == null) continue;
            obj.put("tokenaddr", msgData.tokenAddr);
            obj.put("chain", String.valueOf(msgData.chainId));
            obj.put("tokenid", String.valueOf(msgData.tokenId));
            obj.put("name", tInfo.tokenName);
            obj.put("symbol", tInfo.tokenSymbol);
            obj.put("msg", msgData.message);

            jsonArray.put(obj);
        }

        JSONObject outerObj = new JSONObject();
        outerObj.put("data", jsonArray);

        String messages = outerObj.toString();

        return new ResponseEntity<>(messages, HttpStatus.CREATED);
    }

    private String callSmartContractFunction(Web3j web3j,
                                             Function function, String contractAddress, String walletAddr)
    {
        String encodedFunction = FunctionEncoder.encode(function);

        try
        {
            Transaction transaction
                    = createEthCallTransaction(walletAddr, contractAddress, encodedFunction);
            EthCall response = web3j.ethCall(transaction, DefaultBlockParameterName.LATEST).send();

            List<Type> responseValues = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());

            if (!responseValues.isEmpty())
            {
                return responseValues.get(0).getValue().toString();
            }
            else if (response.hasError() && response.getError().getCode() == 3) //reverted
            {
                return "";
            }
        }
        catch (Exception e)
        {
            //
            e.printStackTrace();
        }

        return null;
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

    private static Function stringParam(String param) {
        return new Function(param,
                Collections.emptyList(),
                Collections.singletonList(new TypeReference<Utf8String>() {
                }));
    }

    private long parseStringValue(String chainIdStr)
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

    private static class TokenInfo
    {
        final String tokenName;
        final String tokenSymbol;

        public TokenInfo(String name, String symbol)
        {
            tokenName = name;
            tokenSymbol = symbol;
        }
    }

    private static class MessageData
    {
        final String tokenAddr;
        final long chainId;
        final long tokenId;
        final String message;

        public MessageData(String tAddr, long cId, long tId, String msg)
        {
            tokenAddr = tAddr;
            chainId = cId;
            tokenId = tId;
            message = msg;
        }
    }
}