package com.example.test.Service;

import com.example.test.Model.tagread;
import com.example.test.Repository.TagRFIDRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Path;

@Service
public class TagRFIDService {
    @Autowired
    private TagRFIDRepository tagRepo;



    public tagread TagMapper() throws FileNotFoundException {
        Gson gson=new Gson();
        tagread tag=gson.fromJson("{\n" +
                "  \"EPC\": \"E98A144587788652562502145639\",\n" +
                "  \"Antenna\": 3,\n" +
                "  \"Timestamp\": 1234567898765,\n" +
                "  \"RSSI\": -5\n" +
                "}",tagread.class);

        if(!((tag.getEPC().length()==24)&&(Integer.toHexString(Integer.parseInt(tag.getEPC())).length()==0)&&(tag.getAntenna()>0)&&(tag.getAntenna()<5)&&((String.valueOf(tag.getTimestamp())).length()==13)&&((tag.getRSSI()<0)&&(tag.getRSSI()>-70))))
        {

            System.out.println("ERREUR");
        }
        return tag;
    }
    public tagread SaveTag() throws FileNotFoundException {
        tagread tag=this.TagMapper();
        tagRepo.saveAndFlush(tag);
        return  tagRepo.save(tag);
    }
}
