package com.example.test.Model;

import com.sun.org.apache.xerces.internal.impl.dv.util.HexBin;

import javax.persistence.*;

@Entity
@Table(name="tagread")
public class tagread {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @Column(name = "epc")
    private String EPC;
    @Column(name="antenna")
    private int Antenna;

    @Column(name = "timestampreader")
    private long Timestamp;
    @Column(name ="rssi")
    private int RSSI;

  /*  public tagread()
    {
        if(!((EPC.length()==24)&&(Integer.toHexString(Integer.parseInt(EPC)).length()==0)&&(Antenna>0)&&(Antenna<5)&&((String.valueOf(Timestamp)).length()==13)&&((RSSI<0)&&(RSSI>-70))))
        {

            System.out.println("ERREUR");
        }
    }
*/

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getEPC() {
        return EPC;
    }

    public void setEPC(String EPC) {
        this.EPC = EPC;
    }

    public int getAntenna() {
        return Antenna;
    }

    public void setAntenna(int antenna) {
        Antenna = antenna;
    }

    public long getTimestamp() {
        return Timestamp;
    }

    public void setTimestamp(long timestamp) {
        Timestamp = timestamp;
    }

    public int getRSSI() {
        return RSSI;
    }

    public void setRSSI(int RSSI) {
        this.RSSI = RSSI;
    }

    @Override
    public String toString() {
        return "tagRFID{" +
                "id=" + id +
                ", EPC='" + EPC + '\'' +
                ", Antenna=" + Antenna +
                ", Timestamp=" + Timestamp +
                ", RSSI=" + RSSI +
                '}';
    }
}
