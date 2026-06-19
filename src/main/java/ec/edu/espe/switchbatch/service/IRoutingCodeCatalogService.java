package ec.edu.espe.switchbatch.service;

import ec.edu.espe.switchbatch.model.SwitchParameter;
import java.util.List;

public interface IRoutingCodeCatalogService {

    boolean isValid(String routingCode);

    /** Returns "ON_US", "OFF_US", or null if the code is not in the catalog. */
    String classify(String routingCode);

    List<SwitchParameter> listAll();
}
