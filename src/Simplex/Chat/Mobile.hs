{-# LANGUAGE DuplicateRecordFields #-}
{-# LANGUAGE LambdaCase #-}
{-# LANGUAGE NamedFieldPuns #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TemplateHaskell #-}
{-# LANGUAGE TypeApplications #-}
{-# OPTIONS_GHC -fobject-code #-}

module Simplex.Chat.Mobile where

import Control.Concurrent.STM
import Control.Exception (SomeException, catch)
import Control.Monad.Except
import Control.Monad.Reader
import qualified Data.Aeson as J
import qualified Data.Aeson.TH as JQ
import Data.Bifunctor (first)
import Data.ByteArray (ScrubbedBytes)
import qualified Data.ByteArray as BA
import qualified Data.ByteString.Base64.URL as U
import Data.ByteString.Char8 (ByteString)
import qualified Data.ByteString.Char8 as B
import qualified Data.ByteString.Lazy.Char8 as LB
import Data.Either (fromRight)
import Data.Functor (($>))
import Data.List (find)
import qualified Data.List.NonEmpty as L
import Data.Maybe (fromMaybe)
import Data.Word (Word8)
import Database.SQLite.Simple (SQLError (..))
import qualified Database.SQLite.Simple as DB
import Foreign.C.String
import Foreign.C.Types (CBool (..), CInt (..), CLong (..))
import Foreign.Ptr
import Foreign.StablePtr
import Foreign.Storable (poke)
import GHC.IO.Encoding (setFileSystemEncoding, setForeignEncoding, setLocaleEncoding)
import Simplex.Chat
import Simplex.Chat.Controller
import Simplex.Chat.Image (readResizeable, resizeImageToSize)
import Simplex.Chat.Library.Commands
import Simplex.Chat.Markdown (ParsedMarkdown (..), parseMaybeMarkdownList)
import Simplex.Chat.Mobile.File
import Simplex.Chat.Mobile.Shared
import Simplex.Chat.Mobile.WebRTC
import Simplex.Chat.Options
import Simplex.Chat.Remote.Types
import Simplex.Chat.Store
import Simplex.Chat.Store.Profiles
import Simplex.Chat.Types
import Simplex.Chat.Util (liftIOEither)
import Simplex.Messaging.Agent.Client (agentClientStore)
import Simplex.Messaging.Agent.Env.SQLite (createAgentStore)
import Simplex.Messaging.Agent.Store.SQLite (MigrationConfirmation (..), MigrationError, closeSQLiteStore, reopenSQLiteStore)
import qualified Simplex.Messaging.Crypto as C
import Simplex.Messaging.Encoding.String
import Simplex.Messaging.Parsers (defaultJSON, dropPrefix, sumTypeJSON)
import Simplex.Messaging.Protocol (AProtoServerWithAuth (..), AProtocolType (..), BasicAuth (..), CorrId (..), ProtoServerWithAuth (..), ProtocolServer (..))
import Simplex.Messaging.Util (catchAll, liftEitherWith, safeDecodeUtf8)
import System.IO (utf8)
import System.Timeout (timeout)

data DBMigrationResult
  = DBMOk
  | DBMInvalidConfirmation
  | DBMErrorNotADatabase {dbFile :: String}
  | DBMErrorMigration {dbFile :: String, migrationError :: MigrationError}
  | DBMErrorSQL {dbFile :: String, migrationSQLError :: String}
  deriving (Show)

$(JQ.deriveToJSON (sumTypeJSON $ dropPrefix "DBM") ''DBMigrationResult)

data APIResponse = APIResponse {corr :: Maybe CorrId, remoteHostId :: Maybe RemoteHostId, resp :: ChatResponse}

$(JQ.deriveToJSON defaultJSON ''APIResponse)

foreign export ccall "chat_migrate_init" cChatMigrateInit :: CString -> CString -> CString -> Ptr (StablePtr ChatController) -> IO CJSONString

foreign export ccall "chat_migrate_init_key" cChatMigrateInitKey :: CString -> CString -> CInt -> CString -> CInt -> Ptr (StablePtr ChatController) -> IO CJSONString

foreign export ccall "chat_close_store" cChatCloseStore :: StablePtr ChatController -> IO CString

foreign export ccall "chat_reopen_store" cChatReopenStore :: StablePtr ChatController -> IO CString

foreign export ccall "chat_send_cmd" cChatSendCmd :: StablePtr ChatController -> CString -> IO CJSONString

foreign export ccall "chat_send_remote_cmd" cChatSendRemoteCmd :: StablePtr ChatController -> CInt -> CString -> IO CJSONString

foreign export ccall "chat_recv_msg" cChatRecvMsg :: StablePtr ChatController -> IO CJSONString

foreign export ccall "chat_recv_msg_wait" cChatRecvMsgWait :: StablePtr ChatController -> CInt -> IO CJSONString

foreign export ccall "chat_parse_markdown" cChatParseMarkdown :: CString -> IO CJSONString

foreign export ccall "chat_parse_server" cChatParseServer :: CString -> IO CJSONString

foreign export ccall "chat_password_hash" cChatPasswordHash :: CString -> CString -> IO CString

foreign export ccall "chat_valid_name" cChatValidName :: CString -> IO CString

foreign export ccall "chat_json_length" cChatJsonLength :: CString -> IO CInt

foreign export ccall "chat_encrypt_media" cChatEncryptMedia :: StablePtr ChatController -> CString -> Ptr Word8 -> CInt -> IO CString

foreign export ccall "chat_decrypt_media" cChatDecryptMedia :: CString -> Ptr Word8 -> CInt -> IO CString

foreign export ccall "chat_write_file" cChatWriteFile :: StablePtr ChatController -> CString -> Ptr Word8 -> CInt -> IO CJSONString

foreign export ccall "chat_write_image" cChatWriteImage :: StablePtr ChatController -> CLong -> CString -> Ptr Word8 -> CInt -> CBool -> IO CJSONString

foreign export ccall "chat_read_file" cChatReadFile :: CString -> CString -> CString -> IO (Ptr Word8)

foreign export ccall "chat_encrypt_file" cChatEncryptFile :: StablePtr ChatController -> CString -> CString -> IO CJSONString

foreign export ccall "chat_decrypt_file" cChatDecryptFile :: CString -> CString -> CString -> CString -> IO CString

foreign export ccall "chat_resize_image_to_str_size" cChatResizeImageToStrSize :: CString -> CLong -> IO CString

-- | check / migrate database and initialize chat controller on success
cChatMigrateInit :: CString -> CString -> CString -> Ptr (StablePtr ChatController) -> IO CJSONString
cChatMigrateInit fp key conf = cChatMigrateInitKey fp key 0 conf 0

cChatMigrateInitKey :: CString -> CString -> CInt -> CString -> CInt -> Ptr (StablePtr ChatController) -> IO CJSONString
cChatMigrateInitKey fp key keepKey conf background ctrl = do
  -- ensure we are set to UTF-8; iOS does not have locale, and will default to
  -- US-ASCII all the time.
  setLocaleEncoding utf8
  setFileSystemEncoding utf8
  setForeignEncoding utf8

  dbPath <- peekCString fp
  dbKey <- BA.convert <$> B.packCString key
  confirm <- peekCAString conf
  r <-
    chatMigrateInitKey dbPath dbKey (keepKey /= 0) confirm (background /= 0) >>= \case
      Right cc -> (newStablePtr cc >>= poke ctrl) $> DBMOk
      Left e -> pure e
  newCStringFromLazyBS $ J.encode r

cChatCloseStore :: StablePtr ChatController -> IO CString
cChatCloseStore cPtr = deRefStablePtr cPtr >>= chatCloseStore >>= newCAString

cChatReopenStore :: StablePtr ChatController -> IO CString
cChatReopenStore cPtr = do
  c <- deRefStablePtr cPtr
  newCAString =<< chatReopenStore c

-- | send command to chat (same syntax as in terminal for now)
cChatSendCmd :: StablePtr ChatController -> CString -> IO CJSONString
cChatSendCmd cPtr cCmd = do
  c <- deRefStablePtr cPtr
  cmd <- B.packCString cCmd
  newCStringFromLazyBS =<< chatSendCmd c cmd

-- | send command to chat (same syntax as in terminal for now)
cChatSendRemoteCmd :: StablePtr ChatController -> CInt -> CString -> IO CJSONString
cChatSendRemoteCmd cPtr cRemoteHostId cCmd = do
  c <- deRefStablePtr cPtr
  cmd <- B.packCString cCmd
  let rhId = Just $ fromIntegral cRemoteHostId
  newCStringFromLazyBS =<< chatSendRemoteCmd c rhId cmd

-- | receive message from chat (blocking)
cChatRecvMsg :: StablePtr ChatController -> IO CJSONString
cChatRecvMsg cc = deRefStablePtr cc >>= chatRecvMsg >>= newCStringFromLazyBS

-- |  receive message from chat (blocking up to `t` microseconds (1/10^6 sec), returns empty string if times out)
cChatRecvMsgWait :: StablePtr ChatController -> CInt -> IO CJSONString
cChatRecvMsgWait cc t = deRefStablePtr cc >>= (`chatRecvMsgWait` fromIntegral t) >>= newCStringFromLazyBS

-- | parse markdown - returns ParsedMarkdown type JSON
cChatParseMarkdown :: CString -> IO CJSONString
cChatParseMarkdown s = newCStringFromLazyBS . chatParseMarkdown =<< B.packCString s

-- | parse server address - returns ParsedServerAddress JSON
cChatParseServer :: CString -> IO CJSONString
cChatParseServer s = newCStringFromLazyBS . chatParseServer =<< B.packCString s

cChatPasswordHash :: CString -> CString -> IO CString
cChatPasswordHash cPwd cSalt = do
  pwd <- B.packCString cPwd
  salt <- B.packCString cSalt
  newCStringFromBS $ chatPasswordHash pwd salt

-- This function supports utf8 strings
cChatValidName :: CString -> IO CString
cChatValidName cName = newCString . mkValidName =<< peekCString cName

-- | returns length of JSON encoded string
cChatJsonLength :: CString -> IO CInt
cChatJsonLength s = fromIntegral . subtract 2 . LB.length . J.encode . safeDecodeUtf8 <$> B.packCString s

-- -- | Resize image at path to match specified dimensions preserving aspect ratio
-- cChatResizeImageToFit :: CString -> CInt -> CInt -> IO CString
-- cChatResizeImageToFit path maxWidth maxHeight = error "todo"

-- -- | Resize image at path to match specified dimensions, cropping the extra pixels
-- cChatResizeImageCrop :: CString -> CInt -> CInt -> IO CString
-- cChatResizeImageCrop path width height = error "todo" -- аватарки

-- -- | Downscale image at path until it fits into specified size
-- cChatResizeImageToDataSize :: CString -> CInt -> IO CString
-- cChatResizeImageToDataSize path maxSize = error "todo"

-- | Downscale image at path until its data-uri encoding fits into specified size.
-- Returns data-uri/base64 encoded image as 0-terminated string.
-- Empty result string means operation failure.
-- The caller must free the result ptr.
cChatResizeImageToStrSize :: CString -> CLong -> IO CString
cChatResizeImageToStrSize fp' maxSize = do
  fp <- peekCString fp'
  res <- runExceptT $ do
    (ri, _) <- liftIOEither $ readResizeable fp
    let resized = resizeImageToSize True previewMinQuality (fromIntegral maxSize) ri
    if LB.length resized > fromIntegral maxSize then throwError "unable to fit" else pure resized
  newCStringFromLazyBS $ fromRight "" res
  where
    previewMinQuality = 20

-- -- | Strip EXIF etc metadata from image, inlplace
-- cChatStripImageMetadata :: CString -> IO CBool
-- cChatStripImageMetadata path = error "todo"

mobileChatOpts :: String -> ChatOpts
mobileChatOpts dbFilePrefix =
  ChatOpts
    { coreOptions =
        CoreChatOpts
          { dbFilePrefix,
            dbKey = "", -- for API database is already opened, and the key in options is not used
            smpServers = [],
            xftpServers = [],
            simpleNetCfg = defaultSimpleNetCfg,
            logLevel = CLLImportant,
            logConnections = False,
            logServerHosts = True,
            logAgent = Nothing,
            logFile = Nothing,
            tbqSize = 1024,
            highlyAvailable = False,
            yesToUpMigrations = False
          },
      deviceName = Nothing,
      chatCmd = "",
      chatCmdDelay = 3,
      chatCmdLog = CCLNone,
      chatServerPort = Nothing,
      optFilesFolder = Nothing,
      optTempDirectory = Nothing,
      showReactions = False,
      allowInstantFiles = True,
      autoAcceptFileSize = 0,
      muteNotifications = True,
      markRead = False,
      maintenance = True
    }

defaultMobileConfig :: ChatConfig
defaultMobileConfig =
  defaultChatConfig
    { confirmMigrations = MCYesUp,
      logLevel = CLLError,
      coreApi = True,
      deviceNameForRemote = "Mobile"
    }

getActiveUser_ :: SQLiteStore -> IO (Maybe User)
getActiveUser_ st = find activeUser <$> withTransaction st getUsers

chatMigrateInit :: String -> ScrubbedBytes -> String -> IO (Either DBMigrationResult ChatController)
chatMigrateInit dbFilePrefix dbKey confirm = chatMigrateInitKey dbFilePrefix dbKey False confirm False

chatMigrateInitKey :: String -> ScrubbedBytes -> Bool -> String -> Bool -> IO (Either DBMigrationResult ChatController)
chatMigrateInitKey dbFilePrefix dbKey keepKey confirm backgroundMode = runExceptT $ do
  confirmMigrations <- liftEitherWith (const DBMInvalidConfirmation) $ strDecode $ B.pack confirm
  chatStore <- migrate createChatStore (chatStoreFile dbFilePrefix) confirmMigrations
  agentStore <- migrate createAgentStore (agentStoreFile dbFilePrefix) confirmMigrations
  liftIO $ initialize chatStore ChatDatabase {chatStore, agentStore}
  where
    initialize st db = do
      user_ <- getActiveUser_ st
      newChatController db user_ defaultMobileConfig (mobileChatOpts dbFilePrefix) backgroundMode
    migrate createStore dbFile confirmMigrations =
      ExceptT $
        (first (DBMErrorMigration dbFile) <$> createStore dbFile dbKey keepKey confirmMigrations)
          `catch` (pure . checkDBError)
          `catchAll` (pure . dbError)
      where
        checkDBError e = case sqlError e of
          DB.ErrorNotADatabase -> Left $ DBMErrorNotADatabase dbFile
          _ -> dbError e
        dbError e = Left . DBMErrorSQL dbFile $ show e

chatCloseStore :: ChatController -> IO String
chatCloseStore ChatController {chatStore, smpAgent} = handleErr $ do
  closeSQLiteStore chatStore
  closeSQLiteStore $ agentClientStore smpAgent

chatReopenStore :: ChatController -> IO String
chatReopenStore ChatController {chatStore, smpAgent} = handleErr $ do
  reopenSQLiteStore chatStore
  reopenSQLiteStore (agentClientStore smpAgent)

handleErr :: IO () -> IO String
handleErr a = (a $> "") `catch` (pure . show @SomeException)

chatSendCmd :: ChatController -> B.ByteString -> IO JSONByteString
chatSendCmd cc = chatSendRemoteCmd cc Nothing

chatSendRemoteCmd :: ChatController -> Maybe RemoteHostId -> B.ByteString -> IO JSONByteString
chatSendRemoteCmd cc rh s = J.encode . APIResponse Nothing rh <$> runReaderT (execChatCommand rh s) cc

chatRecvMsg :: ChatController -> IO JSONByteString
chatRecvMsg ChatController {outputQ} = json <$> readChatResponse
  where
    json (corr, remoteHostId, resp) = J.encode APIResponse {corr, remoteHostId, resp}
    readChatResponse = do
      out@(_, _, cr) <- atomically $ readTBQueue outputQ
      if filterEvent cr then pure out else readChatResponse
    filterEvent = \case
      CRGroupSubscribed {} -> False
      CRGroupEmpty {} -> False
      CRMemberSubSummary {} -> False
      CRPendingSubSummary {} -> False
      _ -> True

chatRecvMsgWait :: ChatController -> Int -> IO JSONByteString
chatRecvMsgWait cc time = fromMaybe "" <$> timeout time (chatRecvMsg cc)

chatParseMarkdown :: ByteString -> JSONByteString
chatParseMarkdown = J.encode . ParsedMarkdown . parseMaybeMarkdownList . safeDecodeUtf8

chatParseServer :: ByteString -> JSONByteString
chatParseServer = J.encode . toServerAddress . strDecode
  where
    toServerAddress :: Either String AProtoServerWithAuth -> ParsedServerAddress
    toServerAddress = \case
      Right (AProtoServerWithAuth protocol (ProtoServerWithAuth ProtocolServer {host, port, keyHash = C.KeyHash kh} auth)) ->
        let basicAuth = maybe "" (\(BasicAuth a) -> enc a) auth
         in ParsedServerAddress (Just ServerAddress {serverProtocol = AProtocolType protocol, hostnames = L.map enc host, port, keyHash = enc kh, basicAuth}) ""
      Left e -> ParsedServerAddress Nothing e
    enc :: StrEncoding a => a -> String
    enc = B.unpack . strEncode

chatPasswordHash :: ByteString -> ByteString -> ByteString
chatPasswordHash pwd salt = either (const "") passwordHash salt'
  where
    salt' = U.decode salt
    passwordHash = U.encode . C.sha512Hash . (pwd <>)
