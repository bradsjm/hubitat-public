unzip ESPHome-API-Library-Bundle.zip -d ESPHome-API-Library-Bundle
cp ESPHome-API-Library.groovy ESPHome-API-Library-Bundle/esphome.espHomeApiHelper.groovy
cd ESPHome-API-Library-Bundle
zip -r ../ESPHome-API-Library-Bundle.zip *
cd ..
rm -r ESPHome-API-Library-Bundle
