package edu.whut.cs.bi.rag.service;
import com.tencent.tcvectordb.client.RPCVectorDBClient;
import com.tencent.tcvectordb.client.VectorDBClient;
import com.tencent.tcvectordb.model.AIDatabase;
import com.tencent.tcvectordb.model.CollectionView;
import com.tencent.tcvectordb.model.param.collectionView.*;
import com.tencent.tcvectordb.model.param.database.ConnectParam;
import com.tencent.tcvectordb.model.param.enums.ParsingTypeEnum;
import com.tencent.tcvectordb.model.param.enums.ReadConsistencyEnum;
import org.springframework.stereotype.Service;
//  腾讯云 service ，暂时 弃用
@Service
public class VectorDBClientOnlineService {
    public String dbName = "BI_Rag";
    public String collectionName = "BI_Rag_Collection";
    public VectorDBClient getDBClient(){
        // 创建VectorDB Client
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withUrl("http://gz-vdb-0untoyqw.sql.tencentcdb.com:8100")
                .withUsername("root")
                .withKey("POqaOv7JDIOW7Rf9cflzwsjOut5GfcKRorOePVWb")
                .withTimeout(60)
                .build();
        VectorDBClient client = new RPCVectorDBClient(connectParam, ReadConsistencyEnum.EVENTUAL_CONSISTENCY);
        return client;
    }

    public void initDBandCollection(VectorDBClient client){
        if(client.IsExistsDatabase(dbName) == false){
            AIDatabase db = client.createAIDatabase(dbName);
            // 如果不存在 collection ，创建一个
            CreateCollectionViewParam collectionParam = CreateCollectionViewParam.newBuilder()
                    .withName(collectionName)
                    .withDescription("standard Docs")
                    .withEmbedding(EmbeddingParams.newBuilder().withEnableWordEmbedding(true).withLanguage(LanguageType.ZH).Build())
                    .withSplitterPreprocess(SplitterPreprocessParams.newBuilder()
                            .withAppendKeywordsToChunkEnum(true)
                            .withAppendTitleToChunkEnum(false)
                            .Build())
                    .withParsingProcess(ParsingProcessParam.newBuilder().withParsingType(ParsingTypeEnum.VisionModel).build())
                    .build();

            db.createCollectionView(collectionParam);
        }
    }

}
