package tn.vas.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Conversion JPA : numéro en clair dans le code, chiffré (déterministe) en base. Voir MsisdnCrypto. */
@Converter
public class MsisdnConverter implements AttributeConverter<String, String> {
    @Override
    public String convertToDatabaseColumn(String attribute) {
        return MsisdnCrypto.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return MsisdnCrypto.decrypt(dbData);
    }
}
