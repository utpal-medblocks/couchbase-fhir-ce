type JWTHeader = {
  alg: string;
  typ: 'JWT';
  kid: string
}

type JWTPayload = {
  iss: string;
  sub: string;
  iat: number;
  exp: number;
  [key: string]: any
}

type DecodedJWT = {
  header: JWTHeader;
  payload: JWTPayload,
  signature: string
}


const NAME_CLAIM = `name`;
const EMAIL_CLIAM = `email`;


export function decodeJWT(token: string): DecodedJWT | null {
  const jwtRegex = /([\w\d-_]+)\.([\w\d-_]+)\.([\w\d-_]+)/;

  if(!jwtRegex.test(token)) return null;

  const [header, payload, signature] = token.split(".")

  const headerObj = base64ToObject(header) as JWTHeader;
  const payloadObj = base64ToObject(payload) as JWTPayload;
  
  return { header: headerObj, payload: payloadObj, signature};
}

function base64ToObject(base64Json: string) {
  const decodedAscii = atob(base64Json);

  const bytesLen = decodedAscii.length;
  const bytes = new Uint8Array(bytesLen);

  for(let i = 0; i < bytesLen; i++) bytes[i] = decodedAscii.charCodeAt(i)

  const decoder = new TextDecoder("utf-8");

  return JSON.parse(decoder.decode(bytes))
}